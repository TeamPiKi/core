package com.depromeet.piki.explore.controller.dto

import com.depromeet.piki.explore.domain.ExplorePick
import com.depromeet.piki.explore.service.dto.ExploreCandidateResult
import com.depromeet.piki.explore.service.dto.ExplorePickCandidatesResult
import com.depromeet.piki.explore.service.dto.ExplorePickDetailResult
import com.depromeet.piki.explore.service.dto.ExplorePickEntryResult
import com.depromeet.piki.explore.service.dto.ExplorePicksResult
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "탐색의 남들은 뭘 골랐을까?")
data class ExplorePicksResponse(
    @field:Schema(
        description = "이 목록을 마지막으로 다시 만든 시각. 아직 만들어진 적이 없으면 null",
        example = "2026-11-27T10:00:00+09:00",
        nullable = true,
    )
    val updatedAt: LocalDateTime?,
    @field:Schema(description = "최근에 고른 순. 같은 상품은 가장 최근에 고른 한 장만 있다")
    val picks: List<ExplorePickResponse>,
) {
    companion object {
        fun from(result: ExplorePicksResult): ExplorePicksResponse =
            ExplorePicksResponse(updatedAt = result.updatedAt, picks = result.entries.map(ExplorePickResponse::from))
    }
}

@Schema(description = "토너먼트 1위 카드 한 장")
data class ExplorePickResponse(
    @field:Schema(description = "1위 id. 1위 상세와 후보 목록에 쓴다", example = "2048")
    val pickId: Long,
    @field:Schema(description = "1위 후보 id. 하트로 내 위시에 담을 때 후보 담기 API 에 넘긴다", example = "4096")
    val candidateId: Long,
    @field:Schema(description = "1위로 고른 시각", example = "2026-11-27T09:58:30+09:00")
    val pickedAt: LocalDateTime,
    @field:Schema(description = "보는 사람이 이미 내 위시에 가진 상품인지. 비로그인이면 false", example = "false")
    val saved: Boolean,
    val tournament: ExploreTournamentView,
    val item: ExploreItemView,
) {
    companion object {
        fun from(result: ExplorePickEntryResult): ExplorePickResponse =
            ExplorePickResponse(
                pickId = result.pick.pickId,
                candidateId = result.pick.candidateId,
                pickedAt = result.pick.pickedAt,
                saved = result.saved,
                tournament = ExploreTournamentView.from(result.pick),
                item = ExploreItemView.from(result.pick.item),
            )
    }
}

@Schema(description = "1위가 나온 토너먼트")
data class ExploreTournamentView(
    @field:Schema(description = "토너먼트 id", example = "301")
    val tournamentId: Long,
    @field:Schema(description = "토너먼트 이름. 만든 사람이 직접 적은 문구다", example = "겨울 패딩 뭐 사지")
    val name: String,
) {
    companion object {
        fun from(pick: ExplorePick): ExploreTournamentView = ExploreTournamentView(tournamentId = pick.tournamentId, name = pick.tournamentName)
    }
}

@Schema(description = "토너먼트 1위의 상세")
data class ExplorePickDetailResponse(
    @field:Schema(description = "1위 id", example = "2048")
    val pickId: Long,
    @field:Schema(description = "1위 후보 id. 하트로 내 위시에 담을 때 후보 담기 API 에 넘긴다", example = "4096")
    val candidateId: Long,
    @field:Schema(description = "1위로 고른 시각", example = "2026-11-27T09:58:30+09:00")
    val pickedAt: LocalDateTime,
    @field:Schema(description = "보는 사람이 이미 내 위시에 가진 상품인지. 비로그인이면 false", example = "false")
    val saved: Boolean,
    @field:Schema(description = "이 상품을 위시에 담은 사람 수", example = "12")
    val savedUserCount: Int,
    val item: ExploreItemView,
    @field:Schema(description = "1위로 고른 사람. folder 는 그 사람이 이 상품을 위시로 담아 공개 폴더에 넣었을 때만 있다")
    val picker: ExploreOwnerView,
    val tournament: ExploreTournamentDetailView,
) {
    companion object {
        fun from(result: ExplorePickDetailResult): ExplorePickDetailResponse =
            ExplorePickDetailResponse(
                pickId = result.pick.pickId,
                candidateId = result.pick.candidateId,
                pickedAt = result.pick.pickedAt,
                saved = result.saved,
                savedUserCount = result.savedUserCount,
                item = ExploreItemView.from(result.pick.item),
                picker = ExploreOwnerView.from(result.picker),
                tournament =
                    ExploreTournamentDetailView(
                        tournamentId = result.pick.tournamentId,
                        name = result.pick.tournamentName,
                        candidateCount = result.candidates.size,
                        candidateImageUrls = result.candidates.map { it.item.imageUrl },
                    ),
            )
    }
}

@Schema(description = "1위가 나온 토너먼트와 후보 요약")
data class ExploreTournamentDetailView(
    @field:Schema(description = "토너먼트 id", example = "301")
    val tournamentId: Long,
    @field:Schema(description = "토너먼트 이름. 만든 사람이 직접 적은 문구다", example = "겨울 패딩 뭐 사지")
    val name: String,
    @field:Schema(description = "후보 목록에 보이는 후보 수. 1위를 포함한다", example = "8")
    val candidateCount: Int,
    @field:Schema(description = "후보 썸네일. 후보 목록과 같은 순서이고 1위가 맨 앞이다")
    val candidateImageUrls: List<String>,
)

@Schema(description = "토너먼트 1위의 후보 목록")
data class ExplorePickCandidatesResponse(
    @field:Schema(description = "1위 id", example = "2048")
    val pickId: Long,
    val tournament: ExploreTournamentView,
    @field:Schema(description = "1위가 맨 앞이고 나머지는 토너먼트에 올라간 순")
    val candidates: List<ExploreCandidateResponse>,
) {
    companion object {
        fun from(result: ExplorePickCandidatesResult): ExplorePickCandidatesResponse =
            ExplorePickCandidatesResponse(
                pickId = result.pick.pickId,
                tournament = ExploreTournamentView.from(result.pick),
                candidates = result.candidates.map(ExploreCandidateResponse::from),
            )
    }
}

@Schema(description = "토너먼트 후보 한 장")
data class ExploreCandidateResponse(
    @field:Schema(description = "후보 id. 내 위시로 담기에 쓴다", example = "4096")
    val candidateId: Long,
    @field:Schema(description = "이 후보가 1위인지", example = "true")
    val isPick: Boolean,
    @field:Schema(description = "보는 사람이 이미 내 위시에 가진 상품인지. 비로그인이면 false", example = "false")
    val saved: Boolean,
    val item: ExploreItemView,
) {
    companion object {
        fun from(result: ExploreCandidateResult): ExploreCandidateResponse =
            ExploreCandidateResponse(
                candidateId = result.candidateId,
                isPick = result.isPick,
                saved = result.saved,
                item = ExploreItemView.from(result.item),
            )
    }
}
