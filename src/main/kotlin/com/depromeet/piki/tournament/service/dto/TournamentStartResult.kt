package com.depromeet.piki.tournament.service.dto

data class TournamentStartResult(
    val tournamentItemId: Long,
    val name: String?,
    val price: Int?,
    val originalPrice: Int?,
    val currency: String?,
    val imageUrl: String?,
)
