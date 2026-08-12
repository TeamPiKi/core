package com.depromeet.piki.auth.service

import com.depromeet.piki.auth.infrastructure.oauth.OAuthProvider
import com.depromeet.piki.auth.infrastructure.oauth.apple.AppleNotificationEventType
import com.depromeet.piki.auth.infrastructure.oauth.apple.AppleNotificationVerifier
import com.depromeet.piki.auth.infrastructure.redis.RefreshTokenStore
import com.depromeet.piki.notification.sse.LocalSseDelivery
import com.depromeet.piki.user.repository.UserDetailRepository
import com.depromeet.piki.user.service.WithdrawalService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

// Apple 서버-서버 알림 처리. 사용자가 Apple 쪽에서 연결을 끊거나(consent-revoked) Apple ID 를
// 삭제(account-delete)하면 Apple 이 이 경로로 알림을 보내고, 그에 맞춰 우리 계정 상태를 동기화한다.
//
// 트랜잭션을 걸지 않는다 — 서명 검증(verify)이 Apple JWKS 를 가져오는 외부 호출이라, 그 latency 동안
// DB 커넥션을 잡으면 안 된다(트랜잭션 경계 규약). 실제 영속화는 WithdrawalService 가 자기 트랜잭션으로 처리한다.
@Service
class AppleNotificationService(
    private val verifier: AppleNotificationVerifier,
    private val userDetailRepository: UserDetailRepository,
    private val withdrawalService: WithdrawalService,
    private val refreshTokenStore: RefreshTokenStore,
    private val localSseDelivery: LocalSseDelivery,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun handle(payloadJwt: String) {
        // 검증 실패(서명·issuer·aud·형식)는 AppleNotificationException(401)로 던져 위조 호출을 거부한다.
        val event = verifier.verify(payloadJwt)
        when (event.type) {
            // Apple ID 삭제 → 주인이 사라진 계정이므로 데이터까지 파기(탈퇴).
            AppleNotificationEventType.ACCOUNT_DELETE ->
                resolveUserId(event.sub)?.let { withdrawalService.withdraw(it) }
            // 앱-Apple 연결 해제 → 세션만 종료(계정·데이터 유지). 재로그인하면 같은 sub 로 기존 계정 복귀.
            AppleNotificationEventType.CONSENT_REVOKED ->
                resolveUserId(event.sub)?.let { terminateSession(it) }
            // Private Relay 이메일 전달 on/off — 우리는 메일을 발송하지 않아 반영할 동작이 없다(로그만).
            AppleNotificationEventType.EMAIL_DISABLED, AppleNotificationEventType.EMAIL_ENABLED ->
                log.info("Apple 이메일 릴레이 상태 변경 이벤트 수신 type={}", event.type)
            // Apple 이 타입을 늘려도 깨지지 않게 흡수(로그만).
            AppleNotificationEventType.UNKNOWN ->
                log.info("미지원 Apple 알림 이벤트 수신 — 무시")
        }
    }

    // sub(Apple subject = socialId) → 우리 userId. 못 찾으면(미가입·이미 탈퇴로 user_detail 파기됨) 멱등하게 무시한다.
    // sub 는 호출 시점에 이미 non-null 이다 — account-delete·consent-revoked 는 AppleNotificationEvent.parse 가
    // sub 를 보장하고(누락은 401 로 차단됨), 이 메서드는 그 둘에서만 호출된다. 여기 null 이 닿으면 parse 를 안 거친
    // 내부 버그이므로 불변식(requireNotNull)으로 막는다(500).
    private fun resolveUserId(sub: String?): UUID? {
        requireNotNull(sub) { "account-delete·consent-revoked 는 parse 가 sub 를 보장하므로 여기 닿으면 안 된다" }
        val userId = userDetailRepository.findByProviderAndSocialId(OAuthProvider.APPLE.name, sub)?.getIdOrNull()
        userId ?: log.info("Apple 알림 대상 유저 없음 — 멱등 무시 (미가입 또는 이미 탈퇴)")
        return userId
    }

    // consent-revoked 세션 종료: refresh token 삭제(재발급 차단) + SSE 연결 즉시 종료.
    // access token denylist 는 쓰지 않는다 — userId 단위라 마킹하면 재로그인으로 받은 새 access token 까지
    // 거부되어 "재로그인 시 기존 계정 복귀"가 깨진다. access token 은 짧은 수명으로 자연 만료에 맡긴다.
    // 계정 차원의 동의 철회라 기기 하나가 아니라 전 세션을 끊는다(#893 으로 키가 세션별로 갈렸다).
    private fun terminateSession(userId: UUID) {
        refreshTokenStore.deleteAll(userId)
        localSseDelivery.closeAll(userId)
        log.info("Apple consent-revoked 세션 종료 userId={}", userId)
    }
}
