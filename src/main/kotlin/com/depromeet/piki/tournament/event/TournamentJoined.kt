package com.depromeet.piki.tournament.event

import com.depromeet.piki.common.event.NotificationEvent
import java.util.UUID

// 토너먼트 참여 — 도메인 사실. actorId 는 참여자.
data class TournamentJoined(
    val tournamentId: Long,
    val actorId: UUID,
) : NotificationEvent
