package com.depromeet.piki.admin.access

// /stats period 옵션. preset 은 MetricsService.resolveRange 가 받는 값과 동일하게 두어(오늘/어제/7d/30d)
// 집계 로직을 그대로 재사용한다. label 은 embed 제목용 한국어.
enum class StatsPeriod(
    val preset: String,
    val label: String,
) {
    TODAY("today", "오늘"),
    YESTERDAY("yesterday", "어제"),
    LAST_7D("7d", "최근 7일"),
    LAST_30D("30d", "최근 30일"),
    ;

    companion object {
        // 옵션값(preset 문자열, /stats choices) → enum. 누락·미지원은 오늘로(가장 좁은 안전 기본).
        fun from(value: String?): StatsPeriod = entries.firstOrNull { it.preset == value } ?: TODAY
    }
}
