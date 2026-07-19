package com.depromeet.piki.tournament.service.dto

import com.depromeet.piki.tournament.domain.Tournament
import com.depromeet.piki.tournament.domain.TournamentStatus
import java.time.LocalDateTime

data class TournamentSummary(
    val tournamentId: Long,
    val name: String,
    val status: TournamentStatus,
    val createdAt: LocalDateTime,
    val participantProfileImages: List<String>,
    // 카드 대표 썸네일 — 최근 등록 아이템 중 이미지 있는 것 최대 2장. 이미지 있는 아이템이 없으면 빈 리스트.
    val thumbnailUrls: List<String>,
) {
    companion object {
        fun of(
            tournament: Tournament,
            participantProfileImages: List<String>,
            thumbnailUrls: List<String>,
            effectiveStatus: TournamentStatus = tournament.status,
        ): TournamentSummary =
            TournamentSummary(
                tournamentId = tournament.getId(),
                name = tournament.name,
                status = effectiveStatus,
                createdAt = tournament.createdAt,
                participantProfileImages = participantProfileImages,
                thumbnailUrls = thumbnailUrls,
            )
    }
}
