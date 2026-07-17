package com.depromeet.piki.notification.controller.dto

import com.depromeet.piki.notification.service.dto.NotificationDeleteCommand
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NotificationDeleteRequestTest {
    // all XOR ids — 정확히 한쪽만 유효 (읽음 요청과 동형 계약).
    @ParameterizedTest(name = "[{index}] {3}")
    @MethodSource("selectionCases")
    fun `validSelection XOR`(
        all: Boolean?,
        ids: List<Long>?,
        expected: Boolean,
        @Suppress("UNUSED_PARAMETER") description: String,
    ) {
        assertEquals(expected, NotificationDeleteRequest(all = all, ids = ids).validSelection)
    }

    @Test
    fun `toCommand - all=true 면 All`() {
        assertIs<NotificationDeleteCommand.All>(NotificationDeleteRequest(all = true).toCommand())
    }

    @Test
    fun `toCommand - ids 면 Ids 로 그 목록을 담는다`() {
        val command = NotificationDeleteRequest(ids = listOf(1L, 2L)).toCommand()
        val ids = assertIs<NotificationDeleteCommand.Ids>(command)
        assertEquals(listOf(1L, 2L), ids.ids)
    }

    @Test
    fun `validSelection - all 단독은 통과한다`() {
        assertTrue(NotificationDeleteRequest(all = true).validSelection)
    }

    @Test
    fun `validSelection - 빈 ids 는 선택 없음으로 본다`() {
        assertFalse(NotificationDeleteRequest(ids = emptyList()).validSelection)
    }

    companion object {
        @JvmStatic
        fun selectionCases(): Stream<Arguments> =
            Stream.of(
                Arguments.of(true, null, true, "all 단독 → 유효"),
                Arguments.of(null, listOf(1L), true, "ids 단독 → 유효"),
                Arguments.of(true, listOf(1L), false, "둘 다 → 무효"),
                Arguments.of(null, null, false, "둘 다 없음 → 무효"),
                Arguments.of(false, null, false, "all=false + ids 없음 → 무효"),
                Arguments.of(null, emptyList<Long>(), false, "빈 ids → 무효"),
                Arguments.of(false, listOf(1L), true, "all=false + ids → ids 단독으로 유효"),
            )
    }
}
