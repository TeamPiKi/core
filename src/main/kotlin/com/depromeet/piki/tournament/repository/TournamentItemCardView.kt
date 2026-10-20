package com.depromeet.piki.tournament.repository

import java.util.UUID

interface TournamentItemCardView {
    val userId: UUID
    val tournamentId: Long
    val tournamentItemId: Long
}
