package com.depromeet.piki.notification.fcm.service

import com.depromeet.piki.common.logging.SensitiveData
import com.depromeet.piki.notification.controller.dto.NotificationSsePayload
import com.depromeet.piki.notification.domain.Notification
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
import com.google.firebase.messaging.ApnsConfig
import com.google.firebase.messaging.Aps
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.MulticastMessage
import com.google.firebase.messaging.WebpushConfig
import com.google.firebase.messaging.WebpushFcmOptions
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Component
import com.google.firebase.messaging.Notification as FcmNotification

// FcmMessageSender 의 운영 구현 — FirebaseMessaging 멀티캐스트 발송(#245).
// FirebaseApp 빈이 있을 때만(@ConditionalOnBean) 뜬다 — 키 없는 로컬/테스트에선 FirebaseConfig 가
// FirebaseApp 을 안 만들어 이 빈도 안 뜨고, PushNotificationChannel 이 발송을 no-op 으로 건너뛴다.
@Component
@ConditionalOnBean(FirebaseApp::class)
class FirebaseMessageSender(
    firebaseApp: FirebaseApp,
) : FcmMessageSender {
    private val log = LoggerFactory.getLogger(javaClass)
    private val messaging = FirebaseMessaging.getInstance(firebaseApp)

    // tokens 를 멀티캐스트로 발송하고, FCM 이 무효라고 답한(앱 삭제·토큰 폐기) 죽은 토큰을 모아 반환한다.
    // 표시 메시지(title/body)에 수신자 안읽음 수(badge)를 실어 OS 아이콘 badge 를 갱신한다(#487).
    override fun send(
        tokens: List<String>,
        notification: Notification,
        badge: Int,
    ): FcmSendResult {
        val result = sendMulticast(tokens) { chunk -> buildDisplayMessage(chunk, notification, badge) }
        // 어떤 페이로드(type·refId)를 몇 토큰에 보내 몇 건 성공/실패했고, 실패 사유(FCM code)별 분포 + 죽은 토큰 정리 수 + badge.
        // 렌더된 title/body 는 닉네임 등 PII 를 담을 수 있어 싣지 않는다 — 라우팅 식별자(type·refId·kind)만.
        log.info(
            // getIdOrNull — DevFcmController(/dev/fcm/push)는 영속화 안 한 throwaway Notification 을 넘긴다.
            // getId() 면 "id 없음" 예외로 발송 자체가 500 으로 깨진다. 로그용 식별자라 미영속이면 null 로 둔다.
            "FCM 발송 결과 notificationId={} type={} kind={} refId={} badge={} 토큰={} 성공={} 실패={} 실패사유={} 죽은토큰정리={}",
            notification.getIdOrNull(),
            notification.type,
            notification.domainKind(),
            notification.refId,
            badge,
            tokens.size,
            result.successCount,
            tokens.size - result.successCount,
            result.failureByCode,
            result.staleTokens.size,
        )
        return result
    }

    // 읽음 후 갱신 안읽음 수만 silent(data-only)로 보내 OS 아이콘 badge 를 내린다(#487). 표시 블록 없음.
    override fun sendBadgeSync(
        tokens: List<String>,
        badge: Int,
    ): FcmSendResult {
        val result = sendMulticast(tokens) { chunk -> buildSilentMessage(chunk, badge) }
        log.info(
            "FCM badge 동기화(silent) 결과 badge={} 토큰={} 성공={} 실패={} 실패사유={} 죽은토큰정리={}",
            badge,
            tokens.size,
            result.successCount,
            tokens.size - result.successCount,
            result.failureByCode,
            result.staleTokens.size,
        )
        return result
    }

    // 멀티캐스트 발송 공통 루프 — 청크별로 buildMessage 가 만든 메시지를 보내고, 성공 수·코드별 실패·죽은 토큰을 집계한다.
    // 한 청크 발송이 통째로 실패해도(네트워크 등) 나머지 청크는 계속 시도한다 — 부분 실패를 전체 실패로 키우지 않는다.
    // 표시/silent 발송이 메시지 빌더만 다르고 결과 집계·죽은 토큰 정리·청크 격리는 동일하므로 한 곳으로 모은다.
    private fun sendMulticast(
        tokens: List<String>,
        buildMessage: (List<String>) -> MulticastMessage,
    ): FcmSendResult {
        val stale = mutableListOf<String>()
        // 발송 결과 집계 — 성공 수와 실패 사유(FCM messagingErrorCode)별 분포를 모은다. 토큰 원문은 크리덴셜이라 지문(maskToken)으로만 남긴다.
        var success = 0
        val failureCodes = mutableMapOf<FcmFailureCode, Int>()
        tokens.chunked(MULTICAST_LIMIT).forEach { chunk ->
            val response =
                runCatching { messaging.sendEachForMulticast(buildMessage(chunk)) }
                    .getOrElse { e ->
                        // 토큰은 민감 정보라 로그에 남기지 않는다 — 크기만. 청크 통째 실패는 (실패=토큰−성공)으로 드러난다.
                        log.warn("FCM 멀티캐스트 청크 발송 실패 chunkSize={}", chunk.size, e)
                        return@forEach
                    }
            response.responses.forEachIndexed { i, result ->
                if (result.isSuccessful) {
                    success++
                    return@forEachIndexed
                }
                val code = result.exception?.messagingErrorCode
                failureCodes.merge(FcmFailureCode.from(code), 1, Int::plus)
                if (isStaleToken(code)) {
                    stale += chunk[i]
                    log.info("FCM 죽은 토큰 감지 → 정리 대상 token={} code={}", SensitiveData.maskToken(chunk[i]), code)
                } else {
                    // 토큰 무관 실패(요청 오류·일시 오류 등)는 토큰을 지우지 않고 코드만 남긴다(어떤 이슈로 실패했는지 = FCM 반환 코드).
                    log.warn("FCM 발송 실패(토큰 보존) token={} code={}", SensitiveData.maskToken(chunk[i]), code)
                }
            }
        }
        return FcmSendResult(staleTokens = stale, successCount = success, failureByCode = failureCodes)
    }

    // 표시용 title/body + 클라 라우팅용 data 를 실은 멀티캐스트 메시지. 백그라운드 수신 시 클라가 data 로 딥링크를 복원한다.
    // 셰입의 단일 소스는 NotificationSsePayload.from() — SSE·히스토리와 같은 곳에서 title/body·kind·라우팅 값을 만들어
    // 채널이 달라도 내용이 어긋나지 않는다. FCM 은 그 payload 를 표시 블록(title/body)과 data(문자열 맵)로 인코딩만 한다(toFcmData).
    // badge 는 표시 메시지에 함께 실어(iOS aps.badge / Android notificationCount) OS 아이콘 badge 를 갱신한다(#487).
    private fun buildDisplayMessage(
        tokens: List<String>,
        notification: Notification,
        badge: Int,
    ): MulticastMessage {
        val payload = NotificationSsePayload.from(notification)
        return MulticastMessage
            .builder()
            .addAllTokens(tokens)
            .setNotification(
                FcmNotification
                    .builder()
                    .setTitle(payload.title)
                    .setBody(payload.body)
                    .build(),
            ).apply { toFcmData(payload).forEach { (key, value) -> putData(key, value) } }
            .applyDisplayPlatformConfig(badge)
            .build()
    }

    // 읽음 후 badge 동기화용 silent 메시지(#487) — 표시 블록(setNotification) 없이 data 만 싣는다.
    // data 는 badge_sync 디스크리미네이터 + 갱신 안읽음 수(unreadCount). Android 클라가 백그라운드 핸들러에서 이 data 로
    // notifee badge 를 갱신하고, iOS 는 aps.badge(content-available)로 OS 가 직접 갱신한다.
    private fun buildSilentMessage(
        tokens: List<String>,
        badge: Int,
    ): MulticastMessage =
        MulticastMessage
            .builder()
            .addAllTokens(tokens)
            .apply { buildBadgeSyncData(badge).forEach { (key, value) -> putData(key, value) } }
            .applySilentPlatformConfig(badge)
            .build()

    // 표시 메시지의 플랫폼별 옵션 — FCM 이 각 토큰의 플랫폼에 맞는 config 만 적용하므로(iOS 토큰엔 APNS, Android 토큰엔 Android,
    // 웹 토큰엔 WebPush) 한 발송이 세 플랫폼을 다 커버한다. 따라서 user_devices.platform 컬럼 없이도 안전하고, iOS 발송에도 무해하다.
    private fun MulticastMessage.Builder.applyDisplayPlatformConfig(badge: Int): MulticastMessage.Builder =
        this
            // iOS(APNS) — 백그라운드 표시 시 소리 + 아이콘 badge(절대값). 포그라운드 표시는 클라가 SSE 로 처리해 억제한다.
            .setApnsConfig(
                ApnsConfig
                    .builder()
                    .setAps(Aps.builder().setSound("default").setBadge(badge).build())
                    .build(),
            )
            // Android — 8.0+ 는 채널이 있어야 알림이 표시된다. 이 channelId 는 Android 클라가 동일 id 로
            // NotificationChannel 을 생성해야 매칭된다(FE 와 공유하는 상수). HIGH 로 헤드업 표시.
            // notificationCount 로 런처 badge 숫자를 싣는다 — OEM·런처 의존이라 점/숫자 표시는 기기마다 다르다(보장 불가).
            .setAndroidConfig(
                AndroidConfig
                    .builder()
                    .setPriority(AndroidConfig.Priority.HIGH)
                    .setNotification(
                        AndroidNotification
                            .builder()
                            .setChannelId(ANDROID_CHANNEL_ID)
                            .setDefaultSound(true)
                            .setNotificationCount(badge)
                            .build(),
                    ).build(),
            )
            // Web — 알림 클릭 시 이동할 링크. 아이콘 등 웹 전용 옵션·VAPID·서비스워커는 웹 클라 붙일 때 채운다.
            .setWebpushConfig(
                WebpushConfig
                    .builder()
                    .setFcmOptions(WebpushFcmOptions.withLink(WEB_CLICK_LINK))
                    .build(),
            )

    // silent(badge 동기화) 메시지의 플랫폼별 옵션 — 표시 없이 badge 만 내린다.
    private fun MulticastMessage.Builder.applySilentPlatformConfig(badge: Int): MulticastMessage.Builder =
        this
            // iOS — content-available(백그라운드 깨움) + aps.badge(절대값). alert/sound 없음 → 무음·무표시.
            // background push 는 apns-push-type=background, apns-priority=5 가 규약(Apple) — 누락 시 APNs 가 거절할 수 있다.
            .setApnsConfig(
                ApnsConfig
                    .builder()
                    .putHeader(APNS_HEADER_PUSH_TYPE, APNS_PUSH_TYPE_BACKGROUND)
                    .putHeader(APNS_HEADER_PRIORITY, APNS_PRIORITY_BACKGROUND)
                    .setAps(Aps.builder().setContentAvailable(true).setBadge(badge).build())
                    .build(),
            )
            // Android — data-only + HIGH 로 백그라운드/종료 상태에서도 클라 핸들러를 깨운다. AndroidNotification 미설정 → 표시 안 함.
            .setAndroidConfig(
                AndroidConfig
                    .builder()
                    .setPriority(AndroidConfig.Priority.HIGH)
                    .build(),
            )

    companion object {
        // FCM MulticastMessage 한 번에 최대 500 토큰 (Firebase 제한).
        private const val MULTICAST_LIMIT = 500

        // Android 알림 채널 id — Android 클라가 같은 id 로 NotificationChannel 을 만들어야 8.0+ 에서 표시된다(FE 합의 상수).
        private const val ANDROID_CHANNEL_ID = "piki_default"

        // 웹 푸시 클릭 시 이동할 링크(https 필수) — 웹 프론트 붙을 때 실제 경로로 조정.
        // piki.day 도메인 이관(#488): 웹 프론트 apex(Vercel)가 piki.day 로 이전됨.
        private const val WEB_CLICK_LINK = "https://piki.day"

        // iOS silent(background) push 규약 헤더(#487) — content-available 푸시는 이 둘이 있어야 APNs 가 백그라운드로 전달한다.
        private const val APNS_HEADER_PUSH_TYPE = "apns-push-type"
        private const val APNS_PUSH_TYPE_BACKGROUND = "background"
        private const val APNS_HEADER_PRIORITY = "apns-priority"
        private const val APNS_PRIORITY_BACKGROUND = "5"

        // 발송 실패 토큰 중 "정리 대상(죽은 토큰)" 판정 — FirebaseMessaging 응답 구조와 무관한 순수 정책이라
        // companion 으로 분리해 단위 테스트로 분기를 망라한다(FirebaseApp 없이 검증).
        // UNREGISTERED(앱 삭제·토큰 폐기)만 보수적으로 삭제한다. INVALID_ARGUMENT 는 토큰이 아닌 요청·메시지
        // 파라미터 문제에도 올 수 있어, 그걸 stale 로 보면 코드 버그가 정상 토큰을 대량 삭제할 위험이 있다 →
        // 삭제하지 않고 로그로 남긴다(send 의 else 분기). 네트워크·일시 오류·코드 부재도 보존해 재시도된다.
        internal fun isStaleToken(code: MessagingErrorCode?): Boolean = code == MessagingErrorCode.UNREGISTERED

        // 백그라운드 수신 시 클라가 딥링크를 복원하려고 읽는 data 키 — FE 와 공유하는 contract.
        // 키 이름은 NotificationSsePayload 의 필드명과 일치시켜, 클라가 SSE/FCM 어느 채널로 받든 같은 키로 같은 알림을 다루게 한다.
        // id 는 채널 무관 dedup(SSE·FCM 중복 수신 시 같은 알림으로 합침)과 푸시 탭 → 읽음 처리(POST /read {ids:[id]})의 키다(#246).
        private const val DATA_KEY_ID = "id"
        private const val DATA_KEY_TYPE = "type"
        private const val DATA_KEY_REF_ID = "refId"
        private const val DATA_KEY_KIND = "kind"
        private const val DATA_KEY_TOURNAMENT_ID = "tournamentId"
        private const val DATA_KEY_TOURNAMENT_ITEM_ID = "tournamentItemId"
        private const val DATA_KEY_WISH_ID = "wishId"

        // silent badge 동기화 data 키(#487) — 표시 알림이 아니라 badge 갱신 신호임을 type=badge_sync 로 구분한다.
        // 이 값은 NotificationType 의 어떤 값과도 겹치지 않는 예약 디스크리미네이터다 — 클라는 type=badge_sync 면
        // 표시하지 않고 unreadCount 로 notifee badge 만 갱신한다(FE 와 공유하는 contract).
        private const val DATA_VALUE_BADGE_SYNC = "badge_sync"
        private const val DATA_KEY_UNREAD_COUNT = "unreadCount"

        // NotificationSsePayload 를 FCM data(키→값 문자열 맵)로 인코딩한다. 값은 payload(=from())가 이미 만든 것을 읽기만 하고
        // (kind·refId·라우팅 재계산 없음 — SSE 와 단일 소스), 여기선 문자열 평탄화만 한다. kind 는 전 알림 공통이라 항상 싣고,
        // 토너먼트 좌표는 그 셰입에만 싣는다. title/body 는 표시 블록(setNotification)이 담당해 data 에 중복하지 않고,
        // isRead(항상 false)·createdAt(수신시점)도 딥링크 복원에 불필요해 싣지 않는다(lean contract).
        // FirebaseMessaging 호출과 무관한 순수 매핑이라 companion 으로 분리해 단위 테스트로 분기를 망라한다(FirebaseApp 없이 검증).
        internal fun toFcmData(payload: NotificationSsePayload): Map<String, String> =
            buildMap {
                put(DATA_KEY_ID, payload.id.toString())
                put(DATA_KEY_TYPE, payload.type.name)
                put(DATA_KEY_KIND, payload.kind.name)
                put(DATA_KEY_REF_ID, payload.refId.toString())
                if (payload is NotificationSsePayload.TournamentRouted) {
                    put(DATA_KEY_TOURNAMENT_ID, payload.tournamentId.toString())
                    put(DATA_KEY_TOURNAMENT_ITEM_ID, payload.tournamentItemId.toString())
                }
                // wishId 는 WISH 파싱 알림에만, 값이 있을 때만 싣는다(과거 행은 null → 키 생략, 클라 폴백).
                if (payload is NotificationSsePayload.WishParsing) {
                    payload.wishId?.let { put(DATA_KEY_WISH_ID, it.toString()) }
                }
            }

        // silent badge 동기화 메시지의 data 맵(#487). FirebaseMessaging 호출과 무관한 순수 매핑이라 companion 으로 분리해
        // 단위 테스트로 검증한다(FirebaseApp 없이). FCM data 값은 문자열만 가능해 badge 를 toString 으로 평탄화한다.
        internal fun buildBadgeSyncData(badge: Int): Map<String, String> =
            mapOf(
                DATA_KEY_TYPE to DATA_VALUE_BADGE_SYNC,
                DATA_KEY_UNREAD_COUNT to badge.toString(),
            )
    }
}
