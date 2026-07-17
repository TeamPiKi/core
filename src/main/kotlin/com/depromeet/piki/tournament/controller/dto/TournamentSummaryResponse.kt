package com.depromeet.piki.tournament.controller.dto

import com.depromeet.piki.tournament.domain.TournamentStatus
import com.depromeet.piki.tournament.service.dto.TournamentSummary
import java.time.LocalDateTime

data class TournamentSummaryResponse(
    val tournamentId: Long,
    val name: String,
    val status: TournamentStatus,
    val createdAt: LocalDateTime,
    val participantProfileImages: List<String>,
    // 카드 대표 썸네일 URL — 최근 등록 아이템 중 이미지 있는 것 최대 2장 (없으면 빈 배열).
    val thumbnailUrls: List<String>,
) {
    companion object {
        fun from(summary: TournamentSummary): TournamentSummaryResponse =
            TournamentSummaryResponse(
                tournamentId = summary.tournamentId,
                name = summary.name,
                status = summary.status,
                createdAt = summary.createdAt,
                participantProfileImages = summary.participantProfileImages,
                thumbnailUrls = summary.thumbnailUrls,
            )
    }
}
