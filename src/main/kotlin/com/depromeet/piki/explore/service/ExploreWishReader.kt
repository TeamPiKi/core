package com.depromeet.piki.explore.service

import com.depromeet.piki.explore.domain.ExploreItem
import com.depromeet.piki.explore.repository.ExploreWishJpaRepository
import com.depromeet.piki.explore.service.dto.ExploreWishDetailResult
import com.depromeet.piki.explore.service.dto.GetExploreWishQuery
import com.depromeet.piki.item.domain.CardQuery
import com.depromeet.piki.item.service.ItemCard
import com.depromeet.piki.item.service.ItemCardService
import com.depromeet.piki.product.source.SourcePlatformResolver
import com.depromeet.piki.user.service.UserService
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.domain.WishException
import com.depromeet.piki.wishlist.repository.WishRepository
import com.depromeet.piki.wishlist.service.dto.WishResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// 피드와 달리 Redis 를 거치지 않고 그 순간의 DB 로 판정한다. 피드가 한 주기 늦어도 담은 사람은 여기서 가려진다
@Service
class ExploreWishReader(
    private val wishRepository: WishRepository,
    private val userService: UserService,
    private val exploreOwnerReader: ExploreOwnerReader,
    private val exploreWishJpaRepository: ExploreWishJpaRepository,
    private val itemCardService: ItemCardService,
    private val sourcePlatformResolver: SourcePlatformResolver,
) {
    // 상품은 숨기는 대상이 아니라 주인이 프로필을 비공개로 두었어도 내려간다. 가리는 것은 담은 사람과 폴더다
    @Transactional(readOnly = true)
    fun getWish(query: GetExploreWishQuery): ExploreWishDetailResult {
        val wish = wishRepository.findById(query.wishId) ?: throw WishException.notFound()
        val itemId = wish.itemId ?: throw WishException.notFound()
        val owner = userService.findActiveByIds(listOf(wish.userId)).firstOrNull() ?: throw WishException.notFound()
        val ownerResult = exploreOwnerReader.ownerOf(query.viewerId, owner, wish.getId())
        val card =
            ownerResult?.let { itemCardService.cardOf(wish.cardQuery()) }
                ?: cardWithoutOwnerInput(wish, itemId, query.viewerId)
        return ExploreWishDetailResult(
            wish = WishResult(wish = wish, card = card, sourcePlatform = sourcePlatformResolver.resolve(card.sourceLink)),
            owner = ownerResult,
            saved = isSavedBy(query.viewerId, itemId),
            savedUserCount = exploreWishJpaRepository.countLiveByItemId(itemId).toInt(),
        )
    }

    // 담은 사람이 가려졌으면 그 사람이 직접 입력한 값도 내리지 않는다. 보는 사람 기준의 값(기계가 추출한 값이나
    // 보는 사람 자신의 입력)만 쓰고, 그런 값이 없으면 보여줄 상품이 없다
    private fun cardWithoutOwnerInput(
        wish: Wish,
        itemId: Long,
        viewerId: UUID?,
    ): ItemCard {
        val shownTo = viewerId ?: ExploreItem.NOBODY
        val card = itemCardService.cardOf(CardQuery(slot = wish.slot, itemId = itemId, owner = shownTo))
        val display = card.display ?: throw WishException.notFound()
        if (display.userInput && !display.isOwnedBy(shownTo)) throw WishException.notFound()
        return card
    }

    private fun isSavedBy(
        viewerId: UUID?,
        itemId: Long,
    ): Boolean {
        viewerId ?: return false
        return wishRepository.findByItemIdsAndUserId(listOf(itemId), viewerId).isNotEmpty()
    }
}
