package com.depromeet.piki.explore.repository

import java.time.LocalDateTime
import java.util.UUID

interface ExplorePickRow {
    val pickId: Long
    val pickerId: UUID
    val tournamentId: Long
    val tournamentName: String
    val candidateId: Long
    val itemId: Long
    val pickedAt: LocalDateTime
}
