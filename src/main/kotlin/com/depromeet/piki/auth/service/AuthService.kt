package com.depromeet.piki.auth.service

import com.depromeet.piki.auth.exception.AuthException
import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.auth.infrastructure.redis.RefreshOutcome
import com.depromeet.piki.auth.infrastructure.redis.RefreshTokenStore
import com.depromeet.piki.auth.service.dto.SignupResult
import com.depromeet.piki.auth.service.dto.TokenPair
import com.depromeet.piki.common.logging.SensitiveData
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.service.UserService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AuthService(
    private val userService: UserService,
    private val jwtProvider: JwtProvider,
    private val refreshTokenStore: RefreshTokenStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun createGuest(): SignupResult {
        val user = userService.createGuest()
        val tokenPair = issueTokenPair(user)
        log.info("게스트 생성 userId={}", user.id)
        return SignupResult(tokenPair = tokenPair, user = user)
    }

    fun createMember(nickname: String): SignupResult {
        // 닉네임 원문은 PII 라 싣지 않는다 — 생성 사실과 userId 만.
        val user = userService.createMember(nickname)
        val tokenPair = issueTokenPair(user)
        log.info("회원 생성 userId={}", user.id)
        return SignupResult(tokenPair = tokenPair, user = user)
    }

    // Dev 전용. 기존 user 의 token pair 를 발급해 임의 user 시나리오를 재현할 수 있게 한다.
    // OAuth 통합 (epic #122) 전까지의 임시 endpoint 와 같은 결로 묶여 운영 노출 차단 예정 (#177 후속).
    fun issueTokenForExistingUser(userId: UUID): SignupResult {
        val user = userService.findActiveById(userId)
        val tokenPair = issueTokenPair(user)
        return SignupResult(tokenPair = tokenPair, user = user)
    }

    // 갱신 거부는 모두 클라이언트 계약 위반(만료·위조·재사용 토큰)이라 info 로 사유를 구분해 남긴다 —
    // prod 401 디버깅의 핵심: "왜 거부됐나"(파싱 실패/탈퇴/저장 토큰 불일치)를 traceId·userId 와 함께 본다.
    // refresh 토큰 원문은 크리덴셜이라 지문(maskToken)으로만 찍는다.
    //
    // generate-first: 후보 토큰쌍을 먼저 만들어 store 에 넘긴다. store 가 회전을 원자 수행하므로,
    // 동시 요청은 한쪽만 회전하고 나머지는 grace replay 로 같은 토큰으로 수렴한다 (회전 race → 로그아웃 차단).
    fun refresh(refreshToken: String): TokenPair {
        val payload =
            jwtProvider.parseRefreshToken(refreshToken) ?: run {
                log.info("토큰 갱신 거부 사유=refresh 토큰 파싱 실패(만료·위조) token={}", SensitiveData.maskToken(refreshToken))
                throw AuthException.invalidToken()
            }
        val userId = payload.userId
        // sid 없는 토큰 = #893 배포 이전 발급분. 세션 슬롯을 특정할 수 없어 재로그인을 유도한다.
        // 별도 사유로 남긴다 — 배포 후 refresh TTL(14일) 동안 이 로그가 줄어드는지로 이행 상황을 본다.
        val sessionId =
            payload.sessionId ?: run {
                log.info("토큰 갱신 거부 사유=sid 없는 레거시 refresh 토큰(재로그인 필요) userId={}", userId)
                throw AuthException.invalidToken()
            }
        // 탈퇴 여부를 여기서 직접 본다(findActiveById 아님) — 갱신 거부는 409 가 아니라 거부 사유 로그 + 401 이라,
        // 활성 조회에 위임하면 응답 계약이 바뀐다.
        val user = userService.findById(userId)
        user.deletedAt?.let {
            log.info("토큰 갱신 거부 사유=탈퇴 유저 userId={}", userId)
            throw AuthException.invalidToken()
        }

        val candidateAccess = jwtProvider.generateAccessToken(user.id, user.identityType)
        // 회전은 세션을 이어가는 것이라 sid 를 그대로 물려준다 — 새로 발급하면 매 회전마다 슬롯이 갈려 세션이 끊긴다.
        val candidateRefresh = jwtProvider.generateRefreshToken(user.id, sessionId)
        return when (val outcome = refreshTokenStore.rotateOrReplay(userId, sessionId, refreshToken, candidateRefresh)) {
            is RefreshOutcome.Rotated -> {
                log.info("토큰 갱신 성공 userId={} sessionId={}", userId, sessionId)
                TokenPair(accessToken = candidateAccess, refreshToken = candidateRefresh)
            }
            is RefreshOutcome.Replayed -> {
                log.info("토큰 갱신 동시 요청 grace replay userId={} sessionId={}", userId, sessionId)
                TokenPair(accessToken = candidateAccess, refreshToken = outcome.refreshToken)
            }
            is RefreshOutcome.Expired -> {
                log.info(
                    "토큰 갱신 거부 사유=저장된 refresh 토큰 없음(만료·이미 소비·해당 세션 로그아웃) userId={} sessionId={}",
                    userId,
                    sessionId,
                )
                throw AuthException.invalidToken()
            }
            is RefreshOutcome.ReuseDetected -> {
                // store 가 이미 warn(session invalidated) 으로 도난 의심을 남겼다. 여기선 거부 사유만 info 로.
                log.info(
                    "토큰 갱신 거부 사유=refresh 토큰 재사용 감지(회전 후·grace 밖) userId={} sessionId={}",
                    userId,
                    sessionId,
                )
                throw AuthException.invalidToken()
            }
        }
    }

    // 이 기기만 로그아웃한다(#893). 다른 기기의 세션은 유지된다.
    // refreshToken 이 없으면 어느 세션인지 특정할 수 없다 — 그때는 안전한 쪽(전 세션 정리)으로 떨어뜨린다.
    // 이는 #893 이전의 동작이기도 해서, 토큰을 안 보내는 기존 클라이언트의 체감이 바뀌지 않는다.
    fun logout(
        userId: UUID,
        refreshToken: String?,
    ) {
        val sessionId =
            refreshToken?.let { jwtProvider.parseRefreshToken(it) }?.sessionId ?: run {
                refreshTokenStore.deleteAll(userId)
                log.info("로그아웃(전 세션) 사유=refresh 토큰 없음·sid 없음 userId={}", userId)
                return
            }
        refreshTokenStore.delete(userId, sessionId)
        log.info("로그아웃 userId={} sessionId={}", userId, sessionId)
    }

    fun createTokensForUser(user: User): TokenPair {
        user.deletedAt?.let {
            log.info("토큰 발급 거부: 탈퇴 유저 userId={}", user.id)
            throw AuthException.invalidToken()
        }
        return issueTokenPair(user)
    }

    // 로그인 1회 = 세션 1개. 여기서 sid 를 새로 발급하므로 기기가 늘어도 서로의 슬롯을 덮어쓰지 않는다(#893).
    private fun issueTokenPair(user: User): TokenPair {
        val sessionId = jwtProvider.newSessionId()
        val accessToken = jwtProvider.generateAccessToken(user.id, user.identityType)
        val refreshToken = jwtProvider.generateRefreshToken(user.id, sessionId)
        refreshTokenStore.save(user.id, sessionId, refreshToken)
        return TokenPair(accessToken = accessToken, refreshToken = refreshToken)
    }
}
