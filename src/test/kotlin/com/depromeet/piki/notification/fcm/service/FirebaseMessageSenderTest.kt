package com.depromeet.piki.notification.fcm.service

import com.depromeet.piki.notification.controller.dto.NotificationSsePayload
import com.depromeet.piki.notification.domain.Notification
import com.depromeet.piki.notification.domain.NotificationKind
import com.depromeet.piki.notification.domain.NotificationRouting
import com.depromeet.piki.notification.domain.NotificationType
import com.depromeet.piki.support.withId
import com.google.firebase.messaging.MessagingErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 죽은 토큰 판정 분기 망라(#245). FirebaseMessaging 응답과 무관한 순수 정책이라 FirebaseApp 없이 단위로 검증한다.
// (멀티캐스트 chunk 루프·발송 실패 스킵은 FirebaseMessaging 호출에 강결합이라 여기서 다루지 않는다 —
//  채널 레벨 fan-out·정리는 PushNotificationChannelIntegrationTest 가 stub 으로 덮는다.)
class FirebaseMessageSenderTest {
    @Test
    fun `UNREGISTERED 만 정리 대상이다`() {
        assertTrue(FirebaseMessageSender.isStaleToken(MessagingErrorCode.UNREGISTERED))
    }

    // INVALID_ARGUMENT 는 토큰이 아닌 요청·메시지 문제에도 와서 보존한다(정상 토큰 대량 삭제 방지).
    // 그 외 일시 오류도 재시도 위해 보존.
    @ParameterizedTest
    @EnumSource(
        value = MessagingErrorCode::class,
        names = ["UNREGISTERED"],
        mode = EnumSource.Mode.EXCLUDE,
    )
    fun `UNREGISTERED 외 에러코드는 보존한다`(code: MessagingErrorCode) {
        assertFalse(FirebaseMessageSender.isStaleToken(code))
    }

    @Test
    fun `에러코드가 없으면 보존한다`() {
        assertFalse(FirebaseMessageSender.isStaleToken(null))
    }

    // toFcmData 는 NotificationSsePayload(=from(), SSE 와 단일 소스)를 FCM data 로 인코딩 — 셰입별 키 셋 분기를 단위로 망라한다(#408).
    // kind 는 전 알림 공통이라 라우팅 유무와 무관하게 항상 실리고, 토너먼트 좌표만 셰입에 따라 갈린다(#473 고도화).
    // data 키는 FE 와 공유하는 contract 라 부분 단언이 아니라 exact-map 으로 고정한다 — 폐기 키(category·imageUrl) 부재와
    // 예상 못한 새 키 유입을 한 단언이 함께 잡는다.
    @Test
    fun `라우팅 없는 알림의 FCM data 는 id·type·kind·refId 네 키뿐이다`() {
        val payload =
            NotificationSsePayload.Reference(
                id = 1,
                type = NotificationType.TOURNAMENT_JOINED,
                kind = NotificationKind.TOURNAMENT,
                title = "참가했어요",
                body = "",
                refId = 77,
                isRead = false,
                createdAt = LocalDateTime.of(2026, 6, 8, 10, 0, 0),
            )

        val data = FirebaseMessageSender.toFcmData(payload)

        assertEquals(
            mapOf("id" to "1", "type" to "TOURNAMENT_JOINED", "kind" to "TOURNAMENT", "refId" to "77"),
            data,
        )
    }

    @Test
    fun `토너먼트 라우팅 알림의 FCM data 는 공통 네 키에 좌표 두 개가 더해진 여섯 키다`() {
        val payload =
            NotificationSsePayload.TournamentRouted(
                id = 2,
                type = NotificationType.ITEM_PARSING_COMPLETED,
                kind = NotificationKind.TOURNAMENT,
                title = "상품 정보가 저장됐어요",
                body = "",
                refId = 513,
                isRead = false,
                createdAt = LocalDateTime.of(2026, 6, 8, 10, 0, 0),
                tournamentId = 99,
                tournamentItemId = 555,
            )

        val data = FirebaseMessageSender.toFcmData(payload)

        assertEquals(
            mapOf(
                "id" to "2",
                "type" to "ITEM_PARSING_COMPLETED",
                "kind" to "TOURNAMENT",
                "refId" to "513",
                "tournamentId" to "99",
                "tournamentItemId" to "555",
            ),
            data,
        )
    }

    // 엔티티 → payload → data 왕복 하나 — kind 가 from() 의 파생(type + routingKind)에서 오고 toFcmData 가 그걸 그대로
    // 읽는다는 연결을 지킨다. id 는 항상 실린다(#246) — 미영속 엔티티엔 withId 로 부여한다.
    @Test
    fun `위시 파싱 알림은 kind=WISH 만 더 싣고 토너먼트 키는 생략한다`() {
        val notification =
            Notification(UUID.randomUUID(), NotificationType.ITEM_PARSING_COMPLETED, "제목", "본문", 11L, NotificationRouting.Wish(null))
                .withId(43L)

        val data = FirebaseMessageSender.toFcmData(NotificationSsePayload.from(notification))

        assertEquals(
            mapOf(
                "id" to "43",
                "type" to "ITEM_PARSING_COMPLETED",
                "kind" to "WISH",
                "refId" to "11",
            ),
            data,
        )
    }

    // silent badge 동기화(#487) data — 표시 알림이 아니라 badge 갱신 신호임을 type=badge_sync 로 구분하고 unreadCount 만 싣는다.
    // type=badge_sync 는 NotificationType 의 어떤 값과도 겹치지 않는 예약 디스크리미네이터다(클라가 표시 알림과 구분하는 키).
    @Test
    fun `badge 동기화 data 는 type=badge_sync 와 unreadCount 만 싣는다`() {
        val data = FirebaseMessageSender.buildBadgeSyncData(3)

        assertEquals(mapOf("type" to "badge_sync", "unreadCount" to "3"), data)
    }

    @Test
    fun `badge 동기화 data 의 unreadCount 0 도 문자열로 실린다`() {
        // 전부 읽어 0 이 된 경우 — 0 을 실어야 클라가 badge 를 0 으로 내린다(누락 시 안 내려감).
        val data = FirebaseMessageSender.buildBadgeSyncData(0)

        assertEquals(mapOf("type" to "badge_sync", "unreadCount" to "0"), data)
    }
}
