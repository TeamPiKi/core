package com.depromeet.piki.auth.service

import com.depromeet.piki.auth.infrastructure.oauth.OAuthUserInfo
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.domain.UserException
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
    private val guestPlayTakeover: GuestPlayTakeover,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun resolveUser(
        userInfo: OAuthUserInfo,
        currentUserId: UUID?,
    ): User {
        // 1. 이미 가입된 소셜 → 그 user 로 로그인. (재방문 / 게스트의 소셜이 이미 타계정 → 그 계정 로그인 = 게스트 포기)
        //    게스트를 포기하더라도 그 세션의 토너먼트 플레이는 회원 계정으로 옮긴다(#1081) — 승격 갈래와 달리
        //    userId 가 바뀌어, 안 옮기면 방금까지 하던 판이 "참여자가 아님" 이 된다.
        loginExisting(userInfo)?.let { member ->
            currentUserId?.let { guestId -> takeOverGuestPlay(guestId, member.id, userInfo.provider.name) }
            return member
        }

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
                // 합류도 게스트를 포기하는 것이라 1번과 같은 결과가 된다 — 여기서도 플레이를 옮긴다(#1081).
                val joined = loginExisting(userInfo) ?: throw e
                takeOverGuestPlay(guestId, joined.id, userInfo.provider.name)
                return joined
            }
        }

        // 3. 순수 신규 가입 → MEMBER 생성 + 소셜 연결
        return try {
            createSocialUserAndLinkRetryingNickname(userInfo).also {
                log.info("신규 소셜 회원 가입 userId={} provider={}", it.id, userInfo.provider)
            }
        } catch (e: DataIntegrityViolationException) {
            // 동시 충돌: 다른 요청이 먼저 같은 소셜로 가입 → 그 user 로 합류 (내가 만든 user 는 REQUIRED tx 롤백으로 폐기).
            // 방어적으로 복구한 비정상 경합이라 warn.
            log.warn("신규 가입 중 소셜 선점 충돌 → 기존 계정 합류 provider={}", userInfo.provider)
            loginExisting(userInfo) ?: throw e
        }
    }

    // 소셜 신규 가입의 닉네임 충돌 재시도(#920). 닉네임은 자동 생성이라 '중복'이 사용자 입력 오류가 아니고,
    // 생성과 저장 사이 race 로만 충돌한다 — 게스트 생성(UserService.createGuest)이 같은 이유로 재시도하는 것과 같은 결.
    //
    // 재시도가 여기(트랜잭션 밖)에 있어야 하는 이유: createSocialUserAndLink 는 @Transactional 이라 그 안에서
    // 재시도하면 첫 충돌에 트랜잭션이 rollback-only 로 마킹돼 이후 시도가 커밋될 수 없다. 이 서비스는 비트랜잭션이라
    // 매 시도가 REQUIRED 로 새 트랜잭션을 열고 닫는다.
    //
    // 닉네임 충돌만 삼킨다 — 소셜 선점 충돌(user_details unique)은 그대로 던져 호출부의 '기존 계정 합류' 분기가
    // 받게 한다. 둘을 뭉뚱그리면 닉네임 race 가 소셜 충돌로 오진돼, 선점되지 않은 소셜을 loginExisting 으로
    // 찾다 실패하고 500 이 된다.
    private fun createSocialUserAndLinkRetryingNickname(userInfo: OAuthUserInfo): User {
        repeat(SOCIAL_NICKNAME_MAX_ATTEMPTS) {
            try {
                return socialAccountWriter.createSocialUserAndLink(userInfo)
            } catch (e: DataIntegrityViolationException) {
                if (!userService.isNicknameConflict(e)) throw e
                log.info("소셜 가입 닉네임 충돌 → 재발급 재시도 provider={}", userInfo.provider)
            }
        }
        throw UserException.nicknameGenerationFailed()
    }

    // 기존 가입자 재로그인 경로. email 은 provider 가 준 값으로 backfill·최신 유지하되(#442), "매 로그인 write"가
    // 아니라 값이 실제로 바뀐 경우에만 upsert 한다 — findByProviderAndSocialId 로 이미 읽은 UserDetail 의 email 과
    // 비교해, 동일하거나 provider 가 안 준(null) 재로그인(대부분)은 updateEmail 의 새 트랜잭션·SELECT·UPDATE 를
    // 통째로 생략한다(재로그인 hot path).
    // email 은 부가 정보라 upsert 실패(락 경합·일시 DB 오류 등)가 로그인 자체를 막아선 안 된다 — updateEmail 은
    // REQUIRED 새 트랜잭션(호출자 비트랜잭션)이라 실패해도 rollback-only 오염 없이 흡수한다. 실패는 warn 으로
    // 남기고(email 값은 PII 라 미기록) 기존 user 로그인은 그대로 성공시킨다.
    // 게스트 플레이 승계(#1081). 실패가 로그인을 막지 않게 감싸되, 참여 경합은 재시도로 되살린다.
    //
    // 왜 재시도인가: 승계는 "회원이 이미 자리 잡은 방" 을 미리 조회해 건너뛰는데, 그 조회와 이관 사이에 회원이
    // 어느 방에 참여하면 uk_tournament_users 위반으로 트랜잭션이 통째로 롤백된다. 충돌한 방 하나 때문에
    // 무관한 토너먼트까지 전부 못 옮기고, 로그인 뒤엔 게스트 토큰을 다시 안 보내므로 **재시도 기회가 없어
    // 그 한 번이 영구 유실**이 된다. 재조회하면 새로 생긴 회원 행이 점유 목록에 잡혀 그 방은 건너뛰게 되므로
    // 재시도는 반드시 수렴한다.
    //
    // 락으로 막지 않은 이유: 대상 토너먼트를 전부 잠그면 그 방의 정상 참여·시작·매치 기록이 남의 로그인 뒤에서
    // 대기한다. 무관한 사용자에게 확실한 지연을 지우는 대신, 드문 경합을 재시도로 흡수하는 쪽을 택했다.
    // 이 클래스가 비트랜잭션인 덕에 catch 후 재시도가 성립한다(소셜 선점 충돌·닉네임 충돌과 같은 구조).
    private fun takeOverGuestPlay(
        guestId: UUID,
        memberId: UUID,
        provider: String,
    ) {
        repeat(TAKEOVER_MAX_ATTEMPTS) { attempt ->
            try {
                guestPlayTakeover.takeOver(guestId, memberId)
                return
            } catch (e: DataIntegrityViolationException) {
                // 승계 도중 회원이 그 방에 참여해 유니크 충돌. 재조회하면 건너뛰기로 갈린다.
                log.warn(
                    "게스트 플레이 승계 중 참여 경합 → 재시도 guestId={} memberId={} provider={} 시도={}",
                    guestId,
                    memberId,
                    provider,
                    attempt + 1,
                )
            } catch (e: Exception) {
                // 경합이 아닌 실패는 재시도해도 같은 결과다. 로그인은 완료시키고 관측만 남긴다.
                log.warn("게스트 플레이 승계 실패 guestId={} memberId={} provider={}", guestId, memberId, provider, e)
                return
            }
        }
        log.warn(
            "게스트 플레이 승계 재시도 소진 — 플레이가 넘어오지 않았다 guestId={} memberId={} provider={}",
            guestId,
            memberId,
            provider,
        )
    }

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

    companion object {
        // 소셜 가입 닉네임 충돌 재시도 횟수. 게스트(UserService.GUEST_NICKNAME_MAX_ATTEMPTS)와 같은 값으로 둔다 —
        // 같은 race 를 같은 방식으로 흡수하므로 두 경로의 내구성이 달라질 이유가 없다.
        private const val SOCIAL_NICKNAME_MAX_ATTEMPTS = 5

        // 승계 중 참여 경합 재시도 횟수(#1081). 재조회 한 번이면 충돌한 방이 건너뛰기로 갈려 수렴하므로 2회면 충분하고,
        // 여유 1회만 더 둔다. 로그인 경로라 길게 매달리는 것이 사용자에게 더 나쁘다.
        private const val TAKEOVER_MAX_ATTEMPTS = 3
    }
}
