package com.depromeet.piki.metrics.report

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class ReportWindowTest {
    // 화요일(2026-07-14 09:30)에 돌리면 지난 완결 주는 2026-07-06(월) ~ 2026-07-13(월) 반열림이다.
    @Test
    fun `화요일 발송 시 지난 완결 주는 직전 월요일부터 이번 월요일까지다`() {
        val now = LocalDateTime.of(2026, 7, 14, 9, 30)

        val week = ReportWindow.lastCompleteWeek(now)

        assertEquals(LocalDateTime.of(2026, 7, 6, 0, 0), week.from)
        assertEquals(LocalDateTime.of(2026, 7, 13, 0, 0), week.to)
    }

    // 월요일 새벽에 돌려도 "진행 중인 이번 주"가 아니라 완결된 지난주를 잡아야 한다.
    @Test
    fun `월요일 발송 시에도 진행 중 주가 아닌 완결된 지난주를 잡는다`() {
        val now = LocalDateTime.of(2026, 7, 13, 0, 30)

        val week = ReportWindow.lastCompleteWeek(now)

        assertEquals(LocalDateTime.of(2026, 7, 6, 0, 0), week.from)
        assertEquals(LocalDateTime.of(2026, 7, 13, 0, 0), week.to)
    }

    // 일요일 밤(주 마지막)에 돌리면 아직 이번 주가 안 끝났으므로 그 전주를 잡는다.
    @Test
    fun `일요일 발송 시 아직 안 끝난 이번 주가 아니라 그 전주를 잡는다`() {
        val now = LocalDateTime.of(2026, 7, 12, 23, 0)

        val week = ReportWindow.lastCompleteWeek(now)

        assertEquals(LocalDateTime.of(2026, 6, 29, 0, 0), week.from)
        assertEquals(LocalDateTime.of(2026, 7, 6, 0, 0), week.to)
    }

    @Test
    fun `전주는 주간 창을 한 주 뒤로 민 동일 길이 구간이다`() {
        val week = Window(LocalDateTime.of(2026, 7, 6, 0, 0), LocalDateTime.of(2026, 7, 13, 0, 0))

        val prev = ReportWindow.previousWeek(week)

        assertEquals(LocalDateTime.of(2026, 6, 29, 0, 0), prev.from)
        assertEquals(LocalDateTime.of(2026, 7, 6, 0, 0), prev.to)
    }

    @Test
    fun `30일 창은 주간 종료 경계에서 30일 back 이다`() {
        val weekEnd = LocalDateTime.of(2026, 7, 13, 0, 0)

        val trailing = ReportWindow.trailing30d(weekEnd)

        assertEquals(LocalDateTime.of(2026, 6, 13, 0, 0), trailing.from)
        assertEquals(LocalDateTime.of(2026, 7, 13, 0, 0), trailing.to)
    }

    // 월 경계를 넘는 주도 정확히 잡힌다(연/월 경계 회귀 가드).
    @Test
    fun `월 경계를 넘는 지난 완결 주도 정확히 계산된다`() {
        val now = LocalDateTime.of(2026, 3, 3, 10, 0) // 화요일

        val week = ReportWindow.lastCompleteWeek(now)

        assertEquals(LocalDateTime.of(2026, 2, 23, 0, 0), week.from)
        assertEquals(LocalDateTime.of(2026, 3, 2, 0, 0), week.to)
    }
}
