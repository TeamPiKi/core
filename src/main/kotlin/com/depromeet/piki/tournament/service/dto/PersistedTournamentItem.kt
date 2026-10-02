package com.depromeet.piki.tournament.service.dto

// PK 셋을 한 Long 으로 뭉뚱그리면 호출부가 엉뚱한 행을 전이시킴. 응답·삭제는 tournament_item PK, 파싱 전이는 snapshot PK
data class PersistedTournamentItem(
    val itemId: Long,
    val snapshotId: Long,
    val tournamentItemId: Long,
)
