package com.depromeet.piki.notification.service

import com.depromeet.piki.common.config.AsyncConfig
import com.depromeet.piki.notification.domain.Notification
import com.depromeet.piki.notification.fcm.service.FcmMessageSender
import com.depromeet.piki.notification.fcm.service.UserDeviceService
import com.depromeet.piki.notification.repository.NotificationRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

// 알림을 FCM 푸시로 전달하는 채널(#245). @Component 라 dispatcher 의 List<NotificationChannel> 에
// SseNotificationChannel 과 형제로 자동 합류한다 — dispatcher·엔티티·핸들러는 건드리지 않는다.
//
// SSE 채널과 같은 2계층: 이 채널은 얇은 진입점이고, 실제 발송·죽은 토큰 판정은 FcmMessageSender(외부 경계),
// 토큰 조회·정리(트랜잭션)는 UserDeviceService 가 맡는다. send 는 dispatcher 가 트랜잭션 밖에서 호출하므로
// 외부 FCM 호출이 DB 커넥션을 잡지 않는다(CLAUDE.md "외부 호출은 트랜잭션 밖").
//
// FcmMessageSender 는 ObjectProvider 로 받는다 — FirebaseApp 키가 없는 환경에선 FirebaseMessageSender 빈이
// 안 떠서, getIfAvailable() 이 null 이면 발송을 건너뛴다(토큰 수집 API 는 키 없이도 동작).
@Component
class PushNotificationChannel(
    private val senderProvider: ObjectProvider<FcmMessageSender>,
    private val userDeviceService: UserDeviceService,
    private val notificationRepository: NotificationRepository,
    // 타입별 푸시 발송 여부(push_enabled)를 DB 캐시에서 읽는다 — 옛 정적 NotificationChannelPolicy.pushable 을 대체한다.
    // 백오피스가 토글하면 reload 로 캐시가 갱신돼, 코드 배포 없이 타입별 OS 푸시 on/off 가 바뀐다.
    private val templateProvider: NotificationTemplateProvider,
) : NotificationChannel {
    private val log = LoggerFactory.getLogger(javaClass)

    // sender 부재는 로컬에선 정상(키 없음)이지만 운영에선 설정 누락 신호다. 매 발송마다 찍으면
    // 로컬·미설정 환경에서 로그가 폭주하므로, 인스턴스 생애 1회만 warn 해 관측은 되되 스팸은 막는다.
    private val senderMissingWarned = AtomicBoolean(false)

    override fun send(
        userId: UUID,
        notification: Notification,
    ) {
        // 푸시가 꺼진 타입(예: 아이템 추가/삭제 — 인앱 SSE 로 충분)은 OS 트레이 푸시를 보내지 않는다 — 토큰 게이트와 같은 자리의 자기-적용 판단.
        // 발송 여부는 백오피스가 토글하는 DB 값(push_enabled)이라 캐시에서 읽는다(@Volatile 캐시 lookup 이라 싸다).
        if (!templateProvider.find(notification.type).pushEnabled) return
        val sender = sender() ?: return
        val tokens = userDeviceService.findTokens(userId)
        if (tokens.isEmpty()) return
        // 안읽음 수(OS 아이콘 badge)는 persistence.save 커밋 이후·트랜잭션 밖(dispatcher 호출 시점)에 세어 방금 도착한 알림을 포함한다.
        // REST unreadCount 와 동일 소스(카테고리 합)라 앱 내부 badge 와 OS 아이콘 badge 가 같은 수를 가리킨다(#487).
        val badge = notificationRepository.countUnreadByCategory(userId).toBadgeCount()
        val result = sender.send(tokens, notification, badge)
        userDeviceService.removeStaleTokens(result.staleTokens)
    }

    // 읽음 처리 후 갱신된 안읽음 수만 silent 푸시로 보내 OS 아이콘 badge 를 내린다(#487, 멀티 디바이스 동기화).
    // 읽은 기기는 응답 body 로 이미 badge 를 받으므로 이 푸시의 목적은 같은 유저의 다른 기기 동기화다.
    // badge 산정은 호출자(NotificationReadOrchestrator)가 책임진다 — 여기선 갱신 값을 받아 전달만 한다.
    //
    // @Async — 읽음 응답(POST /read)이 외부 FCM latency 에 묶이지 않게 응답 경로에서 떼어 notificationExecutor 로 돌린다
    // (발송 send 가 디스패처의 @Async AFTER_COMMIT 워커에서 도는 것과 대칭). 비동기라 예외가 호출 스레드로 전파되지 않으므로
    // best-effort 흡수도 여기서 한다 — 읽음은 이미 커밋됐고 못 받은 기기는 재진입 시 GET /notifications 로 보정되므로
    // 푸시 실패가 읽음을 깨면 안 된다.
    @Async(AsyncConfig.NOTIFICATION_EXECUTOR)
    fun syncBadge(
        userId: UUID,
        badge: Int,
    ) = syncBadgeBlocking(userId, badge)

    // 동기 배지 발송(best-effort). @Async syncBadge 와 자동삭제 배치(NotificationRetentionBadgeNotifier)가 공유한다 —
    // 후자는 유저당 @Async 를 던지지 않고 한 워커에서 이걸 순차 호출해 실행기 포화(대량 drop)를 피한다. 자기 예외를 삼켜
    // 호출자(비동기 워커·순차 배치)로 전파하지 않는다: 배지 push 실패가 읽음/삭제를 깨면 안 되고, 못 받은 기기는 재진입 시 GET 으로 보정된다.
    fun syncBadgeBlocking(
        userId: UUID,
        badge: Int,
    ) {
        try {
            val sender = sender() ?: return
            val tokens = userDeviceService.findTokens(userId)
            if (tokens.isEmpty()) return
            val result = sender.sendBadgeSync(tokens, badge)
            userDeviceService.removeStaleTokens(result.staleTokens)
        } catch (e: Exception) {
            log.warn("badge 동기화 푸시 실패 userId={}", userId, e)
        }
    }

    private fun sender(): FcmMessageSender? =
        senderProvider.getIfAvailable() ?: run {
            if (senderMissingWarned.compareAndSet(false, true)) {
                log.warn("FCM sender 미주입 — 푸시 발송 건너뜀 (FIREBASE_SERVICE_ACCOUNT 미설정?). 이후 동일 상황은 로그 생략")
            }
            null
        }
}
