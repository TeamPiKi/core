package com.depromeet.piki.notification.domain

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

// domainKind() 위임 검증 — 파생 규칙 자체의 분기 망라는 NotificationKindTest 가 맡는다.
// 여기선 엔티티가 자기 필드 둘(type·routingKind)을 올바른 인자로 넘기는지만 본다.
class NotificationTest {
    private fun notification(
        type: NotificationType,
        routing: NotificationRouting? = null,
    ): Notification = Notification(UUID.randomUUID(), type, "제목", "본문", 11L, routing)

    @Test
    fun `라우팅이 없는 토너먼트 알림의 도메인 축은 TOURNAMENT 다`() {
        assertEquals(NotificationKind.TOURNAMENT, notification(NotificationType.TOURNAMENT_JOINED).domainKind())
    }

    @Test
    fun `위시 출처 파싱 알림의 도메인 축은 WISH 다`() {
        val notification = notification(NotificationType.ITEM_PARSING_COMPLETED, NotificationRouting.Wish)
        assertEquals(NotificationKind.WISH, notification.domainKind())
    }

    @Test
    fun `토너먼트 출처 파싱 알림의 도메인 축은 TOURNAMENT 다`() {
        val notification = notification(NotificationType.ITEM_PARSING_COMPLETED, NotificationRouting.Tournament(99L, 555L))
        assertEquals(NotificationKind.TOURNAMENT, notification.domainKind())
    }
}
