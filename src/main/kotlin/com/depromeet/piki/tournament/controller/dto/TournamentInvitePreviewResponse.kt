package com.depromeet.piki.tournament.controller.dto

import com.depromeet.piki.tournament.service.dto.TournamentInvitePreview

data class TournamentInvitePreviewResponse(
    val tournamentId: Long,
    val tournamentName: String,
    val itemCount: Int,
    val participantCount: Int,
    // 현재 요청 유저의 참여 여부. 인증 토큰이 있으면 그 유저 기준, 없으면 false.
    val joined: Boolean,
) {
    companion object {
        fun from(preview: TournamentInvitePreview): TournamentInvitePreviewResponse =
            TournamentInvitePreviewResponse(
                tournamentId = preview.tournamentId,
                tournamentName = preview.tournamentName,
                itemCount = preview.itemCount,
                participantCount = preview.participantCount,
                joined = preview.joined,
            )
    }
}
