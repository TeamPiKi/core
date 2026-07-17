package com.depromeet.piki.notification.sse

import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.notification.controller.dto.NotificationSsePayload
import com.depromeet.piki.notification.controller.dto.TournamentItemParsed
import com.depromeet.piki.notification.controller.dto.UnreadCountChanged
import com.depromeet.piki.notification.domain.NotificationCategory
import com.depromeet.piki.notification.domain.NotificationType
import com.depromeet.piki.support.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// SSE 이벤트 로그(Redis Stream)에 저장되는 직렬화 산출물의 배포 간 호환성 계약 (테스트 컨벤션 "직렬화/호환성 테스트").
//
// blue-green 전환 중 구·신버전 인스턴스가 같은 스트림을 공유한다. 두 표면이 계약이다:
// 1) payload JSON 셰입 — 스냅샷(src/test/resources/sse-payload/*.json)과 트리 비교. 서버는 payload 를 재파싱하지
//    않고 그대로 replay 하므로 위험은 클라이언트 계약 파손이다. 필드 제거·rename 은 이 스냅샷이 깨져 드러난다
//    (신규 필드 추가는 스냅샷도 함께 갱신 — 클라 하위호환 규칙대로 additive 만 허용).
// 2) 스트림 항목의 필드 키("name"·"payload") — 키를 rename 하면 배포 중 상대 버전이 적재한 항목을 못 읽는다.
//    fixture 의 리터럴 키가 정본이라, 상수를 바꾸면 여기가 깨진다.
//
// 직렬화는 표준 단위 테스트 대신 Spring 관리 ObjectMapper 로 검증한다 — 운영 직렬화 설정(모듈·날짜 포맷)을
// 손으로 복제하면 그 복제가 곧 어긋남의 원천이 되기 때문. IntegrationTestSupport 단일 컨텍스트를 공유해 부팅 비용은 없다.
class SsePayloadSerializationCompatibilityTest : IntegrationTestSupport() {
    @Autowired private lateinit var objectMapper: ObjectMapper

    // 저장값 의미대로 UTC wall-clock. JacksonConfig 의 KST 직렬화기가 같은 순간의 +09:00 로 변환하므로
    // 스냅샷에는 "2026-07-17T21:34:56+09:00" 으로 실린다 — 이 변환 계약 자체도 스냅샷이 고정한다.
    private val createdAt: LocalDateTime = LocalDateTime.of(2026, 7, 17, 12, 34, 56)

    private fun assertMatchesSnapshot(
        payload: Any,
        resource: String,
    ) {
        val actual = objectMapper.readTree(objectMapper.writeValueAsString(payload))
        val expected: JsonNode =
            requireNotNull(javaClass.getResourceAsStream("/sse-payload/$resource")) { "스냅샷 리소스 없음: $resource" }
                .use(objectMapper::readTree)
        assertEquals(expected, actual, "저장 payload 가 스냅샷($resource)과 다르다 — 클라 하위호환(additive) 확인 후 스냅샷을 함께 갱신할 것")
    }

    @Test
    fun `notification Reference payload 는 스냅샷과 호환된다`() {
        assertMatchesSnapshot(
            NotificationSsePayload.Reference(
                id = 1L,
                type = NotificationType.TOURNAMENT_JOINED,
                category = NotificationCategory.ACTIVITY,
                title = "홍길동님이 참가했어요",
                body = "",
                imageUrl = "https://img.test/profiles/actor.png",
                refId = 45L,
                isRead = false,
                createdAt = createdAt,
            ),
            "notification-reference.json",
        )
    }

    @Test
    fun `notification WishParsing payload 는 스냅샷과 호환된다`() {
        assertMatchesSnapshot(
            NotificationSsePayload.WishParsing(
                id = 2L,
                type = NotificationType.ITEM_PARSING_COMPLETED,
                category = NotificationCategory.SYSTEM,
                title = "상품 정보가 저장됐어요",
                body = "",
                imageUrl = "https://img.test/defaults/push-icon.svg",
                refId = 11L,
                isRead = false,
                createdAt = createdAt,
            ),
            "notification-wish-parsing.json",
        )
    }

    @Test
    fun `notification TournamentRouted payload 는 스냅샷과 호환된다`() {
        assertMatchesSnapshot(
            NotificationSsePayload.TournamentRouted(
                id = 3L,
                type = NotificationType.ITEM_PARSING_COMPLETED,
                category = NotificationCategory.SYSTEM,
                title = "상품 정보가 저장됐어요",
                body = "",
                imageUrl = "https://img.test/defaults/push-icon.svg",
                refId = 11L,
                isRead = false,
                createdAt = createdAt,
                tournamentId = 99L,
                tournamentItemId = 555L,
            ),
            "notification-tournament-routed.json",
        )
    }

    @Test
    fun `silent-sync TournamentItemParsed payload 는 스냅샷과 호환된다`() {
        assertMatchesSnapshot(
            TournamentItemParsed(tournamentId = 99L, tournamentItemId = 555L, status = ItemStatus.READY),
            "silent-sync-tournament-item-parsed.json",
        )
    }

    @Test
    fun `silent-sync UnreadCountChanged payload 는 스냅샷과 호환된다`() {
        assertMatchesSnapshot(
            UnreadCountChanged.of(mapOf(NotificationCategory.ACTIVITY to 2L, NotificationCategory.SYSTEM to 1L)),
            "silent-sync-unread-count-changed.json",
        )
    }

    @Test
    fun `스트림 항목 필드 키(name·payload)는 저장 계약으로 고정된다`() {
        // 리터럴 키가 정본 — RedisSseEventLog 의 상수를 rename 하면 구버전이 적재한 항목을 못 읽게 되므로 여기서 깨진다.
        val record =
            RedisSseEventLog.toEventRecord("1752741234567-0", mapOf("name" to "notification", "payload" to """{"id":1}"""))
        assertNotNull(record)
        assertEquals("1752741234567-0", record.id)
        assertEquals("notification", record.eventName)
        assertEquals("""{"id":1}""", record.payloadJson)
    }

    @Test
    fun `계약 밖 항목(필드 누락)은 replay 대상에서 제외된다`() {
        assertNull(RedisSseEventLog.toEventRecord("1-0", mapOf("name" to "notification")))
        assertNull(RedisSseEventLog.toEventRecord("1-0", mapOf("payload" to "{}")))
    }
}
