package com.depromeet.piki.admin.access

import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import com.depromeet.piki.metrics.dashboard.MetricsService
import org.springframework.stereotype.Component

// `/stats period:<오늘|어제|7일|30일> metric:<요약|가입|위시|토너먼트|푸시>` — 대시보드 지표를 Discord 에서 대화형 조회(#664).
// 집계는 MetricsService 를 그대로 재사용하고(중복 0), 여기선 옵션 파싱 + 섹션 embed 표현만 한다. LLM 없음.
// 개발진(developers 명단) 활동은 대시보드 기본과 동일하게 제외한다.
@Component
@ConditionalOnAdminEnabled
class StatsCommandHandler(
    private val metricsService: MetricsService,
) : DiscordCommandHandler {
    override val commandName = "stats"

    override fun handle(interaction: DiscordInteraction): Map<String, Any> {
        val period = StatsPeriod.from(DiscordInteractions.optionValue(interaction.root, "period"))
        val metric = StatsMetric.from(DiscordInteractions.optionValue(interaction.root, "metric"))

        val range = metricsService.resolveRange(period.preset, null, null)
        val snapshot = metricsService.snapshot(range.from, range.to, excludeInternal = true)

        return StatsEmbed.build(metric, snapshot, period.label)
    }
}
