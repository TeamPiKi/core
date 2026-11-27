package com.depromeet.piki.explore.domain

import java.time.LocalDateTime
import java.util.UUID

// 한 사람이 토너먼트 결승에서 고른 1위. 같은 토너먼트라도 참여자마다 1위가 따로 있다.
// Redis 에 JSON 으로 저장된다. 필드를 바꾸면 ExplorePickFeedSerializationCompatibilityTest 의 스냅샷과 맞춘다
data class ExplorePick(
    // 결승 기록(tournament_histories) id
    val pickId: Long,
    val pickerId: UUID,
    val tournamentId: Long,
    val tournamentName: String,
    // 1위로 고른 토너먼트 후보(tournament_items) id
    val candidateId: Long,
    val pickedAt: LocalDateTime,
    val item: ExploreItem,
)
