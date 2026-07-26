package com.depromeet.piki.tournament.service.dto

data class TournamentInvitePreview(
    val tournamentId: Long,
    val tournamentName: String,
    val itemCount: Int,
    val participantCount: Int,
    // 현재 요청 유저의 참여 여부. 미인증(토큰 없음)이면 false.
    val joined: Boolean,
)
