package com.depromeet.piki.explore.service

import com.depromeet.piki.explore.domain.ExploreException
import com.depromeet.piki.explore.domain.ExploreItem
import com.depromeet.piki.explore.repository.ExplorePickJpaRepository
import com.depromeet.piki.explore.repository.ExplorePickRow
import com.depromeet.piki.explore.repository.ExploreWishJpaRepository
import com.depromeet.piki.explore.service.dto.ExploreCandidateResult
import com.depromeet.piki.explore.service.dto.ExploreOwnerResult
import com.depromeet.piki.explore.service.dto.ExplorePickCandidatesResult
import com.depromeet.piki.explore.service.dto.ExplorePickDetailResult
import com.depromeet.piki.explore.service.dto.GetExplorePickQuery
import com.depromeet.piki.item.service.ItemCardService
import com.depromeet.piki.product.source.SourcePlatformResolver
import com.depromeet.piki.tournament.domain.Tournament
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.service.UserService
import com.depromeet.piki.wishlist.repository.WishRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// 위시 상세와 달리 고른 사람이 가려지면 1위 자체를 숨긴다. 1위는 고른 사람의 선택이라 그 사람을 빼고 보여줄 수 없다
@Service
class ExplorePickReader(
    private val explorePickJpaRepository: ExplorePickJpaRepository,
    private val exploreWishJpaRepository: ExploreWishJpaRepository,
    private val userService: UserService,
    private val wishRepository: WishRepository,
    private val exploreOwnerReader: ExploreOwnerReader,
    private val exploreViewerReader: ExploreViewerReader,
    private val itemCardService: ItemCardService,
    private val sourcePlatformResolver: SourcePlatformResolver,
) {
    @Transactional(readOnly = true)
    fun getPick(query: GetExplorePickQuery): ExplorePickDetailResult {
        val row = explorePickJpaRepository.findPick(query.pickId, Tournament.FINAL_ROUND_SIZE) ?: throw ExploreException.pickNotFound()
        val picker = pickerOf(query.viewerId, row)
        val candidates = candidatesOf(query.viewerId, row)
        val pick = row.toPick(candidates.first().item)
        return ExplorePickDetailResult(
            pick = pick,
            picker = picker,
            candidates = candidates,
            savedWishId = candidates.first().savedWishId,
            savedUserCount = exploreWishJpaRepository.countLiveByItemId(pick.item.itemId).toInt(),
        )
    }

    @Transactional(readOnly = true)
    fun getCandidates(query: GetExplorePickQuery): ExplorePickCandidatesResult {
        val row = explorePickJpaRepository.findPick(query.pickId, Tournament.FINAL_ROUND_SIZE) ?: throw ExploreException.pickNotFound()
        pickerOf(query.viewerId, row)
        val candidates = candidatesOf(query.viewerId, row)
        return ExplorePickCandidatesResult(pick = row.toPick(candidates.first().item), candidates = candidates)
    }

    // 게스트는 프로필이 없어 공개를 끌 수 없으므로 내 것이어도 보이지 않는다
    private fun pickerOf(
        viewerId: UUID?,
        row: ExplorePickRow,
    ): ExploreOwnerResult {
        val picker = userService.findActiveByIds(listOf(row.pickerId)).firstOrNull() ?: throw ExploreException.pickNotFound()
        if (picker.identityType != IdentityType.MEMBER) throw ExploreException.pickNotFound()
        val pickersWish = wishRepository.findByItemIdsAndUserId(listOf(row.itemId), picker.getId()).firstOrNull()
        return exploreOwnerReader.ownerOf(viewerId, picker, pickersWish?.getId()) ?: throw ExploreException.pickNotFound()
    }

    // 1위 상품에 보여줄 값이 없으면 상세도 후보 목록도 없다
    private fun candidatesOf(
        viewerId: UUID?,
        row: ExplorePickRow,
    ): List<ExploreCandidateResult> {
        val tournamentItems = explorePickJpaRepository.findCandidates(row.tournamentId)
        val queries = tournamentItems.map { ExploreItem.cardQuery(it.slot, requireNotNull(it.itemId)) }
        val cards = itemCardService.cardsOf(queries)
        val shown =
            tournamentItems.zip(queries).mapNotNull { (tournamentItem, query) ->
                val card = cards.getValue(query)
                ExploreItem.machineExtracted(card, sourcePlatformResolver.resolve(card.sourceLink))?.let { tournamentItem.getId() to it }
            }
        val savedWishIds = viewerId?.let { exploreViewerReader.savedWishIdByItemId(it, shown.map { (_, item) -> item.itemId }) } ?: emptyMap()
        val candidates =
            shown.map { (candidateId, item) ->
                ExploreCandidateResult(
                    candidateId = candidateId,
                    item = item,
                    isPick = candidateId == row.candidateId,
                    savedWishId = savedWishIds[item.itemId],
                )
            }
        if (candidates.none { it.isPick }) throw ExploreException.pickNotFound()
        return candidates.sortedByDescending { it.isPick }
    }
}
