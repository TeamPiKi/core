package com.depromeet.piki.auth.service

import com.depromeet.piki.auth.infrastructure.oauth.OAuthUserInfo
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.repository.UserDetailRepository
import com.depromeet.piki.user.service.UserService
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.util.UUID

// 소셜 아이디 ↔ user 해소. 영속화 mutation 은 SocialAccountWriter(REQUIRED 트랜잭션)에 위임하고,
// 이 클래스는 비트랜잭션 오케스트레이션만 한다 — 동시 첫 로그인 충돌을 catch 후 재조회·합류로 마무리하려면
// resolveUser 자신이 트랜잭션을 들고 있으면 안 되기 때문이다(상위 tx 가 rollback-only 로 오염되면 재조회가 깨진다).
@Service
class SocialAccountService(
    private val userService: UserService,
    private val userDetailRepository: UserDetailRepository,
    private val socialAccountWriter: SocialAccountWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun resolveUser(
        userInfo: OAuthUserInfo,
        currentUserId: UUID?,
    ): User {
        // 1. 이미 가입된 소셜 → 그 user 로 로그인. (재방문 / 게스트의 소셜이 이미 타계정 → 그 계정 로그인 = 게스트 포기)
        loginExisting(userInfo)?.let { return it }

        // 2. 신규 소셜 + 현재 게스트면 → 게스트 계정에 연결 + 승격 (위시·토너먼트 데이터 이어줌)
        currentUserId?.let { guestId ->
            try {
                socialAccountWriter.linkGuestAndPromote(guestId, userInfo)?.let {
                    // 계정 생애 이벤트(게스트→회원 승격) — 승격은 같은 userId 를 유지하므로 데이터 연속성이 보장된다.
                    log.info("게스트 계정 소셜 연결·승격 userId={} provider={}", it.id, userInfo.provider)
                    return it
                }
            } catch (e: DataIntegrityViolationException) {
                // 동시 충돌: 다른 요청이 이 소셜을 먼저 선점 → 그 계정으로 합류 (게스트 포기).
                // 방어적으로 복구한 비정상 경합이라 warn — 빈발하면 동시 로그인 경합 신호다.
                log.warn("게스트 승격 중 소셜 선점 충돌 → 기존 계정 합류 guestId={} provider={}", guestId, userInfo.provider)
                return loginExisting(userInfo) ?: throw e
            }
        }

        // 3. 순수 신규 가입 → MEMBER 생성 + 소셜 연결
        return try {
            socialAccountWriter.createSocialUserAndLink(userInfo).also {
                log.info("신규 소셜 회원 가입 userId={} provider={}", it.id, userInfo.provider)
            }
        } catch (e: DataIntegrityViolationException) {
            // 동시 충돌: 다른 요청이 먼저 같은 소셜로 가입 → 그 user 로 합류 (내가 만든 user 는 REQUIRED tx 롤백으로 폐기).
            // 방어적으로 복구한 비정상 경합이라 warn.
            log.warn("신규 가입 중 소셜 선점 충돌 → 기존 계정 합류 provider={}", userInfo.provider)
            loginExisting(userInfo) ?: throw e
        }
    }

    // 기존 가입자 재로그인 경로. email 은 provider 가 준 값으로 backfill·최신 유지하되(#442), "매 로그인 write"가
    // 아니라 값이 실제로 바뀐 경우에만 upsert 한다 — findByProviderAndSocialId 로 이미 읽은 UserDetail 의 email 과
    // 비교해, 동일하거나 provider 가 안 준(null) 재로그인(대부분)은 updateEmail 의 새 트랜잭션·SELECT·UPDATE 를
    // 통째로 생략한다(재로그인 hot path).
    // email 은 부가 정보라 upsert 실패(락 경합·일시 DB 오류 등)가 로그인 자체를 막아선 안 된다 — updateEmail 은
    // REQUIRED 새 트랜잭션(호출자 비트랜잭션)이라 실패해도 rollback-only 오염 없이 흡수한다. 실패는 warn 으로
    // 남기고(email 값은 PII 라 미기록) 기존 user 로그인은 그대로 성공시킨다.
    private fun loginExisting(userInfo: OAuthUserInfo): User? {
        // 탈퇴(tombstone) 유저는 없는 것으로 취급해 신규 가입 경로를 타게 한다. 탈퇴 시 user_details 는 하드삭제되므로
        // 보통 detail 자체가 안 잡히지만, 파기 전 잔존·경합을 방어해 isActive 로 한 번 더 거른다 — tombstone 을
        // 반환하면 탈퇴한 소셜계정 재로그인 시 죽은 계정을 되살리는 버그가 된다.
        val detail = userDetailRepository.findByProviderAndSocialId(userInfo.provider.name, userInfo.socialId) ?: return null
        val user = userService.findById(detail.getIdOrNull()).takeIf { it.isActive() } ?: return null
        // 값이 실제로 바뀐 경우에만 write. null(미제공)이면 기존 값 보존 위해 생략, 동일하면 불필요한 tx·쿼리를 생략한다.
        userInfo.email
            ?.takeIf { it != detail.email }
            ?.let { newEmail ->
                runCatching { socialAccountWriter.updateEmail(user.id, newEmail) }
                    .onFailure { e -> log.warn("소셜 로그인 email upsert 실패. userId={}, provider={}", user.id, userInfo.provider, e) }
            }
        return user
    }
}
