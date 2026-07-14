package com.depromeet.piki.metrics.report

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

// 리포트 집계 구간(KST LocalDateTime, 반열림 [from, to)).
data class Window(
    val from: LocalDateTime,
    val to: LocalDateTime,
)

// 발송 시각(KST) 기준 리포트 창 계산(순수). 주 시작은 월요일 00:00.
// "지난 완결 주" = 발송 시점이 속한 주의 직전 완결 주(월~일). 진행 중인 이번 주는 제외해 매주 겹치지 않는다.
object ReportWindow {
    // 이번 주 월요일 00:00 을 to, 그 한 주 전을 from 으로 하는 완결 주.
    fun lastCompleteWeek(now: LocalDateTime): Window {
        val thisMonday = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay()
        return Window(thisMonday.minusWeeks(1), thisMonday)
    }

    fun previousWeek(week: Window): Window = Window(week.from.minusWeeks(1), week.from)

    fun trailing30d(weekEnd: LocalDateTime): Window = Window(weekEnd.minusDays(30), weekEnd)
}
