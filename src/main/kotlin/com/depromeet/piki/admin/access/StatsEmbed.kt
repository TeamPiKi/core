package com.depromeet.piki.admin.access

import com.depromeet.piki.metrics.dashboard.MetricsSnapshot

// /stats 가 조회할 지표 섹션. 슬래시커맨드 metric 옵션(choices)과 1:1.
enum class StatsMetric(
    val label: String,
) {
    SUMMARY("요약"),
    SIGNUP("가입"),
    WISH("위시"),
    TOURNAMENT("토너먼트"),
    PUSH("푸시"),
    ;

    companion object {
        // 옵션값(enum 이름 소문자, /stats 커맨드 choices) → enum. 누락·미지원은 요약으로.
        fun from(value: String?): StatsMetric = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SUMMARY
    }
}

// MetricsSnapshot → Discord 인터랙션 embed 응답(type 4, ephemeral) 순수 변환.
// 집계는 MetricsService 가 하고 여기선 표현만 — DB·Spring 의존이 없어 단위로 검증된다.
object StatsEmbed {
    fun build(
        metric: StatsMetric,
        snapshot: MetricsSnapshot,
        periodLabel: String,
    ): Map<String, Any> {
        val fields =
            when (metric) {
                StatsMetric.SUMMARY ->
                    listOf(
                        field("신규 가입", snapshot.signup.within),
                        field("위시 담기", snapshot.wish.total),
                        field("토너먼트 생성", snapshot.tournament.created),
                        field("푸시 발송", snapshot.push.notificationsTotal),
                    )
                StatsMetric.SIGNUP ->
                    listOf(
                        field("신규 가입", snapshot.signup.within),
                        field("회원", snapshot.signup.withinMembers),
                        field("게스트", snapshot.signup.withinGuests),
                        field("게스트→회원 전환", snapshot.signup.guestToMemberConversions),
                    )
                StatsMetric.WISH ->
                    listOf(
                        field("위시 담기", snapshot.wish.total),
                        field("URL", snapshot.wish.fromUrl),
                        field("이미지", snapshot.wish.fromImage),
                        field("파싱 성공률", "${snapshot.wish.parseSuccessRate}%"),
                    )
                StatsMetric.TOURNAMENT ->
                    listOf(
                        field("생성", snapshot.tournament.created),
                        field("참가자", snapshot.tournament.participants),
                        field("완료", snapshot.tournament.completed),
                        field("플레이", snapshot.tournament.plays),
                        field("평균 참가", snapshot.tournament.avgParticipants),
                    )
                StatsMetric.PUSH ->
                    listOf(
                        field("총 발송", snapshot.push.notificationsTotal),
                        field("성공", snapshot.push.deliverySuccess),
                        field("실패", snapshot.push.deliveryFailure),
                        field("근사 CTR", "${snapshot.push.ctrApproxPct}%"),
                    )
            }
        return response("📊 ${metric.label} · $periodLabel", fields)
    }

    private fun field(
        name: String,
        value: Long,
    ): Map<String, Any> = field(name, value.toString())

    private fun field(
        name: String,
        value: String,
    ): Map<String, Any> = mapOf("name" to name, "value" to value, "inline" to true)

    private fun response(
        title: String,
        fields: List<Map<String, Any>>,
    ): Map<String, Any> =
        mapOf(
            "type" to TYPE_CHANNEL_MESSAGE,
            "data" to
                mapOf(
                    "embeds" to listOf(mapOf("title" to title, "color" to COLOR_BLURPLE, "fields" to fields)),
                    "flags" to FLAG_EPHEMERAL,
                ),
        )

    // 인터랙션 응답 타입·ephemeral 플래그는 같은 패키지 DiscordInteractions 가 소유한다(#988) — 여기서 다시
    // 선언하면 Discord 가 값을 바꿀 때 한쪽만 고쳐져, /stats 만 admin 채널에 공개로 올라가는 식으로 조용히 갈린다.
    private const val TYPE_CHANNEL_MESSAGE = DiscordInteractions.TYPE_CHANNEL_MESSAGE
    private const val FLAG_EPHEMERAL = DiscordInteractions.FLAG_EPHEMERAL
    private const val COLOR_BLURPLE = 0x5865F2
}
