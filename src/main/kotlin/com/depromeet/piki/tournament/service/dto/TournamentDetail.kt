package com.depromeet.piki.tournament.service.dto

import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.tournament.domain.TournamentHistory
import java.util.UUID

sealed class TournamentDetail {
    data class Pending(
        val tournamentId: Long,
        val name: String,
        val inviteCode: String,
        val inviteExpiresAt: java.time.LocalDateTime,
        val items: List<ItemDetail>,
        val participants: List<ParticipantDetail>,
        val isOwner: Boolean,
        val isRoot: Boolean,
        // ROOT 가 IN_PROGRESS 로 전환됐으나 이 멤버는 아직 매치를 시작하지 않은 상태.
        // true 이면 클라이언트가 "주최자가 시작했습니다, 지금 시작하세요" UI 를 보여야 한다.
        val ownerStarted: Boolean = false,
        val sourceTournamentId: Long? = null,
    ) : TournamentDetail()

    data class InProgress(
        val tournamentId: Long,
        val name: String,
        val currentRound: Int,
        val lastHistory: HistoryEntry?,
        val remainingItems: List<ItemDetail>,
        // 서버가 브래킷에서 파생한 "지금 치를 매치"(#683). 클라이언트는 페어링·셔플 없이 이걸 그대로 그린다.
        val currentMatch: MatchDetail?,
        val isOwner: Boolean,
        val isRoot: Boolean,
        val sourceTournamentId: Long? = null,
    ) : TournamentDetail()

    data class Completed(
        val tournamentId: Long,
        val name: String,
        val result: List<RankedItem>,
        // 그룹 결과 "조회 가능" 여부 — 완료 플레이어 수 >= 2 (progressive gate).
        val hasGroupResult: Boolean,
        // 소셜(그룹) 토너먼트 여부 — 참여자 수 >= 2 (완료 무관). 결과 화면 배너 "노출" 을 이 값으로 가른다(#975).
        val isGroupTournament: Boolean,
        val isOwner: Boolean,
        val isRoot: Boolean,
        // true: ROOT 소유자 또는 소셜 초대로 참여한 CLONE 소유자 — 결과 화면에서 아이템 담기 허용(위시/링크/이미지).
        // false: 플레이링크 CLONE 소유자 — 소셜 초대 없이 진입한 사용자로, 아이템 담기 불가.
        val canAddItem: Boolean,
        val playLinkExpiresAt: java.time.LocalDateTime?,
        val sourceTournamentId: Long? = null,
    ) : TournamentDetail()

    data class ItemDetail(
        val tournamentItemId: Long,
        val itemId: Long,
        val userId: UUID,
        val name: String?,
        val price: Int?,
        val currency: String?,
        val imageUrl: String?,
        val status: ItemStatus,
    )

    // 한 매치의 두 아이템. ID 만 주고 remainingItems 에서 다시 찾게 하면 클라에 조합 로직이 남으므로
    // 아이템을 통째로 담는다.
    data class MatchDetail(
        val first: ItemDetail,
        val second: ItemDetail,
    )

    data class HistoryEntry(
        val currentRound: Int,
        val firstTournamentItemId: Long,
        val secondTournamentItemId: Long,
        val selectedTournamentItemId: Long,
    ) {
        companion object {
            fun from(history: TournamentHistory): HistoryEntry =
                HistoryEntry(
                    currentRound = history.currentRound,
                    firstTournamentItemId = history.firstTournamentItemId,
                    secondTournamentItemId = history.secondTournamentItemId,
                    selectedTournamentItemId = history.selectedTournamentItemId,
                )
        }
    }

    data class ParticipantDetail(
        val userId: UUID,
        val nickname: String,
        val profileImage: String,
        // 탈퇴 유저 여부. 익명화된 닉네임·프로필 대신 FE 가 이 플래그로 "유저 알수없음" 을 렌더한다.
        val isWithdrawn: Boolean,
        val itemCount: Int,
    )
}
