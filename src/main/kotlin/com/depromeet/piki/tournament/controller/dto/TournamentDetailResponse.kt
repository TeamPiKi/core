package com.depromeet.piki.tournament.controller.dto

import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.tournament.domain.TournamentStatus
import com.depromeet.piki.tournament.service.dto.TournamentDetail
import com.fasterxml.jackson.annotation.JsonInclude
import java.time.LocalDateTime
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
data class TournamentDetailResponse(
    val tournamentId: Long,
    val name: String,
    val status: TournamentStatus,
    val isOwner: Boolean,
    // ROOT(소셜 토너먼트 원본) 이면 true, CLONE(멤버·플레이링크용 복사본) 이면 false.
    // 플레이 링크 공유는 isRoot && isOwner 일 때만 허용된다.
    val isRoot: Boolean,
    // CLONE 토너먼트이면 원본 ROOT id, ROOT 토너먼트이면 null.
    // 게스트 클라이언트는 이 id 로 GET /tournaments/{sourceTournamentId}/group-result 를 호출한다.
    val sourceTournamentId: Long?,
    val pending: PendingData?,
    val inProgress: InProgressData?,
    val completed: CompletedData?,
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class ItemDetailResponse(
        val tournamentItemId: Long,
        val itemId: Long,
        val userId: UUID,
        val name: String?,
        val price: Int?,
        val currency: String?,
        val imageUrl: String?,
        val status: ItemStatus,
    ) {
        companion object {
            fun from(d: TournamentDetail.ItemDetail): ItemDetailResponse =
                ItemDetailResponse(d.tournamentItemId, d.itemId, d.userId, d.name, d.price, d.currency, d.imageUrl, d.status)
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class MatchResponse(
        val first: ItemDetailResponse,
        val second: ItemDetailResponse,
    ) {
        companion object {
            fun from(m: TournamentDetail.MatchDetail): MatchResponse =
                MatchResponse(ItemDetailResponse.from(m.first), ItemDetailResponse.from(m.second))
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class HistoryResponse(
        val currentRound: Int,
        val firstTournamentItemId: Long,
        val secondTournamentItemId: Long,
        val selectedTournamentItemId: Long,
    ) {
        companion object {
            fun from(h: TournamentDetail.HistoryEntry): HistoryResponse =
                HistoryResponse(h.currentRound, h.firstTournamentItemId, h.secondTournamentItemId, h.selectedTournamentItemId)
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class ParticipantResponse(
        val userId: UUID,
        val nickname: String,
        val profileImage: String,
        // 탈퇴 유저면 true. 닉네임·프로필이 익명값이라 FE 가 이 플래그로 "유저 알수없음" 을 렌더한다.
        val isWithdrawn: Boolean,
        val itemCount: Int,
    ) {
        companion object {
            fun from(p: TournamentDetail.ParticipantDetail): ParticipantResponse =
                ParticipantResponse(p.userId, p.nickname, p.profileImage, p.isWithdrawn, p.itemCount)
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class PendingData(
        // ownerStarted=true 이면 초대 기간이 이미 종료됐으므로 null
        val inviteCode: String?,
        val inviteExpiresAt: LocalDateTime?,
        val items: List<ItemDetailResponse>,
        val participants: List<ParticipantResponse>,
        // true: ROOT 가 IN_PROGRESS 전환됐으나 이 멤버는 아직 매치 미시작.
        // 클라이언트는 이 플래그로 "주최자 시작 대기" vs "주최자 시작 완료·지금 시작하세요" UI 를 분기한다.
        val ownerStarted: Boolean = false,
    )

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class InProgressData(
        val currentRound: Int,
        val lastHistory: HistoryResponse?,
        val remainingItems: List<ItemDetailResponse>,
        // 서버가 파생한 "지금 치를 매치"(#683). 클라이언트는 이걸 그대로 그리고 페어링·셔플을 하지 않는다.
        val currentMatch: MatchResponse?,
    )

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class CompletedData(
        val result: List<RankedItemResponse>,
        val hasGroupResult: Boolean,
        // true: ROOT 소유자 또는 소셜 초대 CLONE 소유자 — 아이템 담기 허용(위시/링크/이미지).
        // false: 플레이링크 CLONE 소유자 — 아이템 담기 불가.
        val canAddItem: Boolean,
        val playLinkExpiresAt: LocalDateTime?,
    ) {
        companion object {
            fun from(completed: TournamentDetail.Completed): CompletedData =
                CompletedData(
                    result = completed.result.map { RankedItemResponse.from(it) },
                    hasGroupResult = completed.hasGroupResult,
                    canAddItem = completed.canAddItem,
                    playLinkExpiresAt = completed.playLinkExpiresAt,
                )
        }
    }

    companion object {
        fun from(detail: TournamentDetail): TournamentDetailResponse =
            when (detail) {
                is TournamentDetail.Pending ->
                    TournamentDetailResponse(
                        tournamentId = detail.tournamentId,
                        name = detail.name,
                        // ownerStarted=true 이면 실제 tournament 상태는 IN_PROGRESS 다.
                        status = if (detail.ownerStarted) TournamentStatus.IN_PROGRESS else TournamentStatus.PENDING,
                        isOwner = detail.isOwner,
                        isRoot = detail.isRoot,
                        sourceTournamentId = detail.sourceTournamentId,
                        pending =
                            PendingData(
                                inviteCode = if (detail.ownerStarted) null else detail.inviteCode,
                                inviteExpiresAt = if (detail.ownerStarted) null else detail.inviteExpiresAt,
                                items = detail.items.map { ItemDetailResponse.from(it) },
                                participants = detail.participants.map { ParticipantResponse.from(it) },
                                ownerStarted = detail.ownerStarted,
                            ),
                        inProgress = null,
                        completed = null,
                    )

                is TournamentDetail.InProgress ->
                    TournamentDetailResponse(
                        tournamentId = detail.tournamentId,
                        name = detail.name,
                        status = TournamentStatus.IN_PROGRESS,
                        isOwner = detail.isOwner,
                        isRoot = detail.isRoot,
                        sourceTournamentId = detail.sourceTournamentId,
                        pending = null,
                        inProgress =
                            InProgressData(
                                currentRound = detail.currentRound,
                                lastHistory = detail.lastHistory?.let { HistoryResponse.from(it) },
                                remainingItems = detail.remainingItems.map { ItemDetailResponse.from(it) },
                                currentMatch = detail.currentMatch?.let { MatchResponse.from(it) },
                            ),
                        completed = null,
                    )

                is TournamentDetail.Completed ->
                    TournamentDetailResponse(
                        tournamentId = detail.tournamentId,
                        name = detail.name,
                        status = TournamentStatus.COMPLETED,
                        isOwner = detail.isOwner,
                        isRoot = detail.isRoot,
                        sourceTournamentId = detail.sourceTournamentId,
                        pending = null,
                        inProgress = null,
                        completed = CompletedData.from(detail),
                    )
            }
    }
}
