package com.depromeet.piki.tournament.controller.dto

import com.depromeet.piki.tournament.domain.TournamentStatus
import com.depromeet.piki.tournament.service.dto.TournamentSummary
import java.time.LocalDateTime

data class TournamentSummaryResponse(
    val tournamentId: Long,
    val name: String,
    val status: TournamentStatus,
    val createdAt: LocalDateTime,
    // DEPRECATED — participantCount·playedCount 로 대체됐다. 앱이 새 필드로 전환해 배포되면 제거한다.
    // 그전까지 함께 내리는 이유는 구버전 앱이 이 필드를 non-optional 로 읽고 있으면 목록 화면이 통째로 안 뜨기 때문이다.
    val participantProfileImages: List<String>,
    // 카드의 "함께 담은 N" — 그 토너먼트 참여자 수.
    val participantCount: Int,
    // 카드의 "플레이한 N" — 플레이를 끝까지 마친 사람 수(완주 기준). 아직 아무도 완주 안 했으면 0.
    val playedCount: Int,
    // 카드 대표 썸네일 URL — 최근 등록 아이템 중 READY 인 것의 이미지 최대 2장 (없으면 빈 배열).
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
                participantCount = summary.participantCount,
                playedCount = summary.playedCount,
                thumbnailUrls = summary.thumbnailUrls,
            )
    }
}
