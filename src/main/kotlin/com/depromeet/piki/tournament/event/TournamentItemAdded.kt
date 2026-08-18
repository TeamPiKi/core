package com.depromeet.piki.tournament.event

import com.depromeet.piki.common.event.NotificationEvent
import java.util.UUID

// 토너먼트 아이템 추가 — 도메인 사실. actorId 는 추가한 사용자.
data class TournamentItemAdded(
    val tournamentId: Long,
    val actorId: UUID,
) : NotificationEvent
