package com.depromeet.piki.tournament.event

import com.depromeet.piki.common.event.NotificationEvent
import java.util.UUID

// 멤버/게스트가 내 토너먼트에서 자기 판을 끝까지 플레이해 완료한 사실 — 도메인 사실. recordMatch 결승 시점이며,
// 완료한 참여 행이 주최자 행이 아닐 때만 발행한다. rootTournamentId 는 그 토너먼트, actorId 는 완료한 사람.
// 수신자(ROOT 주최자)에게 "OO님이 완료했어요" 를 알린다. (ROOT 자체 완료=주최자 본인 완료는 TournamentResultReady 로 간다.)
data class TournamentCompleted(
    val rootTournamentId: Long,
    val actorId: UUID,
) : NotificationEvent
