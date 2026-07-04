package com.depromeet.piki.admin.access

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

// 슬래시커맨드 옵션(문자열) → enum 파싱. 잘못된/누락 값은 안전한 기본값으로 떨어진다(공개 인터랙션이라 방어적).
class StatsOptionTest {
    @Test
    fun `metric 옵션값을 enum 으로 파싱하고 없거나 모르면 요약이 기본이다`() {
        assertEquals(StatsMetric.SIGNUP, StatsMetric.from("signup"))
        assertEquals(StatsMetric.WISH, StatsMetric.from("wish"))
        assertEquals(StatsMetric.TOURNAMENT, StatsMetric.from("tournament"))
        assertEquals(StatsMetric.PUSH, StatsMetric.from("push"))
        assertEquals(StatsMetric.SUMMARY, StatsMetric.from("summary"))
        assertEquals(StatsMetric.SUMMARY, StatsMetric.from(null))
        assertEquals(StatsMetric.SUMMARY, StatsMetric.from("unknown"))
    }

    @Test
    fun `period 옵션값을 MetricsService preset·한국어 라벨로 매핑한다`() {
        assertEquals("today", StatsPeriod.from("today").preset)
        assertEquals("오늘", StatsPeriod.from("today").label)
        assertEquals("어제", StatsPeriod.from("yesterday").label)
        assertEquals("7d", StatsPeriod.from("7d").preset)
        assertEquals("최근 7일", StatsPeriod.from("7d").label)
        assertEquals("최근 30일", StatsPeriod.from("30d").label)
    }

    @Test
    fun `period 가 없거나 모르는 값이면 오늘이 기본이다`() {
        assertEquals(StatsPeriod.TODAY, StatsPeriod.from(null))
        assertEquals(StatsPeriod.TODAY, StatsPeriod.from("last-year"))
    }
}
