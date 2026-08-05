package com.depromeet.piki.notification.domain

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationKindTest {
    @ParameterizedTest(name = "{0} + routingKind={1} -> {2}")
    @MethodSource("파생_케이스")
    fun `type 과 라우팅 출처로 알림 종류를 파생한다`(
        type: NotificationType,
        routingKind: NotificationKind?,
        expected: NotificationKind,
    ) {
        assertEquals(expected, NotificationKind.of(type, routingKind))
    }

    @Test
    fun `모든 NotificationType 이 파생 케이스로 검증된다`() {
        // of 는 when 전수라 새 타입을 추가하면 컴파일이 깨지지만, 위 파생 케이스 표가 그 타입을 빠뜨려도 컴파일은 통과한다.
        // 표가 전 타입을 덮는지 런타임으로 확인해 "타입은 늘었는데 검증은 안 는" 구멍을 막는다.
        assertEquals(
            NotificationType.entries.toSet(),
            파생_케이스().map { it.get()[0] as NotificationType }.toSet(),
        )
    }

    @Test
    fun `토너먼트 소셜 알림은 라우팅 출처가 없어도 TOURNAMENT 다`() {
        assertEquals(NotificationKind.TOURNAMENT, NotificationKind.of(NotificationType.TOURNAMENT_JOINED, null))
    }

    @Test
    fun `공지는 SYSTEM 이다`() {
        assertEquals(NotificationKind.SYSTEM, NotificationKind.of(NotificationType.ANNOUNCEMENT, null))
    }

    @Test
    fun `같은 파싱 타입이라도 라우팅 출처에 따라 갈린다`() {
        assertEquals(NotificationKind.WISH, NotificationKind.of(NotificationType.ITEM_PARSING_COMPLETED, NotificationKind.WISH))
        assertEquals(
            NotificationKind.TOURNAMENT,
            NotificationKind.of(NotificationType.ITEM_PARSING_COMPLETED, NotificationKind.TOURNAMENT),
        )
    }

    companion object {
        @JvmStatic
        fun 파생_케이스(): List<Arguments> =
            listOf(
                Arguments.of(NotificationType.TOURNAMENT_JOINED, null, NotificationKind.TOURNAMENT),
                Arguments.of(NotificationType.TOURNAMENT_ITEM_ADDED, null, NotificationKind.TOURNAMENT),
                Arguments.of(NotificationType.TOURNAMENT_ITEM_DELETED, NotificationKind.TOURNAMENT, NotificationKind.TOURNAMENT),
                Arguments.of(NotificationType.TOURNAMENT_STARTED, null, NotificationKind.TOURNAMENT),
                Arguments.of(NotificationType.TOURNAMENT_PLAYED_FROM_LINK, null, NotificationKind.TOURNAMENT),
                Arguments.of(NotificationType.TOURNAMENT_COMPLETED, null, NotificationKind.TOURNAMENT),
                Arguments.of(NotificationType.TOURNAMENT_RESULT_READY, null, NotificationKind.TOURNAMENT),
                Arguments.of(NotificationType.ITEM_PARSING_COMPLETED, NotificationKind.WISH, NotificationKind.WISH),
                Arguments.of(NotificationType.ITEM_PARSING_COMPLETED, NotificationKind.TOURNAMENT, NotificationKind.TOURNAMENT),
                // 라우팅 출처가 없으면 위시 기본값. 정상 흐름에선 resolveRouting 이 항상 출처를 주므로 도달하지 않지만,
                // 기본값을 바꿔도 아무 테스트가 안 깨지던 구멍이라 계약으로 고정한다.
                Arguments.of(NotificationType.ITEM_PARSING_COMPLETED, null, NotificationKind.WISH),
                Arguments.of(NotificationType.ITEM_PARSING_FAILED, NotificationKind.WISH, NotificationKind.WISH),
                Arguments.of(NotificationType.ITEM_PARSING_FAILED, NotificationKind.TOURNAMENT, NotificationKind.TOURNAMENT),
                Arguments.of(NotificationType.ITEM_PARSING_FAILED, null, NotificationKind.WISH),
                Arguments.of(NotificationType.ANNOUNCEMENT, null, NotificationKind.SYSTEM),
            )
    }
}
