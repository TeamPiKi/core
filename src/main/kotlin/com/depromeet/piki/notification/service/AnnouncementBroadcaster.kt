package com.depromeet.piki.notification.service

import com.depromeet.piki.notification.domain.Notification
import com.depromeet.piki.notification.domain.NotificationType
import com.depromeet.piki.notification.fcm.service.FcmMessageSender
import com.depromeet.piki.notification.fcm.service.UserDeviceService
import com.depromeet.piki.notification.repository.NotificationRepository
import com.depromeet.piki.notification.sse.SseNotificationChannel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.util.UUID

// 전체 공지 fan-out(#489). 토큰 보유 유저별로 ANNOUNCEMENT 알림을 만들고(SSE/히스토리) FCM 푸시한 뒤,
// 각 수신자 결과를 onRecipient 로 흘려보낸다 — delivery 건별 기록·진행률 갱신은 호출자(admin)가 맡는다.
//
// 경계: 이 빈은 notification 도메인 소유다(알림 조립·채널·기기 토큰 모두 notification 내부). admin → 이 빈 호출은
// 단방향이라 DDD 경계를 지킨다. refId 에 공지 id 를 박아 notifications.ref_id 로 남기므로, 추후 is_read(클릭)를
// announcement_deliveries 와 user_id + ref_id 로 JOIN 해 "전송 성공 대비 클릭" 퍼널을 뽑을 수 있다.
//
// 트랜잭션: 외부 FCM 호출이 섞여 있어 트랜잭션 밖에서 돈다. 알림 저장만 persistence 빈의 짧은 트랜잭션에 위임한다
// (CLAUDE.md "외부 호출은 트랜잭션 밖"). 유저별로 알림 id 가 달라야 푸시 data.id(읽음 처리 키)가 맞으므로,
// 500 묶음 멀티캐스트가 아니라 유저 단위로 발송한다(같은 내용·다른 알림 id). 대규모에서의 배치·비동기 최적화는 후속.
@Service
class AnnouncementBroadcaster(
    private val userDeviceService: UserDeviceService,
    private val persistence: NotificationPersistenceService,
    private val notificationRepository: NotificationRepository,
    private val sseChannel: SseNotificationChannel,
    private val fcmSenderProvider: ObjectProvider<FcmMessageSender>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun broadcast(
        pushTitle: String,
        pushBody: String,
        pushEnabled: Boolean,
        refId: Long,
        recipients: List<UUID>,
        onRecipient: (RecipientDelivery) -> Unit,
    ) {
        // 알림(SSE·히스토리)엔 공지 body(마크다운 장문)가 아니라 push 전용 문구(≤255)가 들어간다(#561) — notifications.body 한도 준수.
        // push_enabled=false 면 FCM 인터럽트를 보내지 않는다 — sender 를 null 로 둬 기존 "sender 부재 → SKIPPED" 경로를 재사용한다.
        // (공지는 페이지·알림센터엔 그대로 남고 FCM 만 생략. FCM 키 미설정 로컬도 같은 SKIPPED 경로.) SSE 는 인터럽트가 아니라 그대로 둔다.
        val sender = if (pushEnabled) fcmSenderProvider.getIfAvailable() else null
        recipients.forEach { userId ->
            // 수신자 1건 처리 실패(알림 저장·FCM 등)가 전체 fan-out 을 멈추지 않도록 수신자 단위로 흡수한다.
            // 멈추면 일부만 발송된 채 상위가 완료 처리돼 집계 정합성이 깨진다 — 실패는 FAILED 로 기록하고 다음으로.
            val delivery =
                runCatching {
                    val notification = persistence.save(Notification(userId, NotificationType.ANNOUNCEMENT, pushTitle, pushBody, refId))
                    // SSE 는 best-effort(접속 중인 유저만 즉시 수신) — 실패해도 fan-out 을 멈추지 않는다. 히스토리엔 이미 저장됨.
                    runCatching { sseChannel.send(userId, notification) }
                        .onFailure { log.warn("공지 SSE 전달 실패 userId={}", userId, it) }
                    resolvePush(userId, notification, sender)
                }.getOrElse {
                    log.error("공지 수신자 처리 실패 userId={}", userId, it)
                    RecipientDelivery(userId, DeliveryStatus.FAILED, INTERNAL_ERROR_CODE)
                }
            onRecipient(delivery)
        }
    }

    private fun resolvePush(
        userId: UUID,
        notification: Notification,
        sender: FcmMessageSender?,
    ): RecipientDelivery {
        sender ?: return RecipientDelivery(userId, DeliveryStatus.SKIPPED, null)
        val tokens = userDeviceService.findTokens(userId)
        if (tokens.isEmpty()) return RecipientDelivery(userId, DeliveryStatus.NO_TOKEN, null)
        // 공지도 안읽음 알림이라 수신자의 전체 안읽음 수(방금 저장한 공지 포함)를 OS 아이콘 badge 로 싣는다(#487).
        val badge = notificationRepository.countUnread(userId).toBadgeCount()
        val result = sender.send(tokens, notification, badge)
        userDeviceService.removeStaleTokens(result.staleTokens)
        // 한 기기라도 도달하면 그 유저는 받은 것 → SUCCESS. 전 기기 실패면 대표 코드와 함께 FAILED.
        return when {
            result.successCount > 0 -> RecipientDelivery(userId, DeliveryStatus.SUCCESS, null)
            // delivery 행의 fcm_code 는 String(우리 INTERNAL_ERROR 등도 담음)이라 enum 을 name 으로 박는다.
            else -> RecipientDelivery(userId, DeliveryStatus.FAILED, result.dominantFailureCode?.name)
        }
    }

    companion object {
        // FCM 코드가 아니라 우리 쪽 수신자 처리 예외를 나타내는 fcm_code 값(FCM 에러코드와 구분).
        private const val INTERNAL_ERROR_CODE = "INTERNAL_ERROR"
    }
}
