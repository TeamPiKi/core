package com.depromeet.piki.explore.service

import com.depromeet.piki.block.repository.UserBlockRepository
import com.depromeet.piki.explore.repository.ExploreWishJpaRepository
import com.depromeet.piki.explore.service.dto.ExploreFolderResult
import com.depromeet.piki.explore.service.dto.ExploreOwnerResult
import com.depromeet.piki.explore.service.dto.ExploreWishDetailResult
import com.depromeet.piki.explore.service.dto.GetExploreWishQuery
import com.depromeet.piki.friendship.repository.FriendshipRepository
import com.depromeet.piki.item.domain.CardQuery
import com.depromeet.piki.item.service.ItemCard
import com.depromeet.piki.item.service.ItemCardService
import com.depromeet.piki.product.source.SourcePlatformResolver
import com.depromeet.piki.profile.domain.ProfileExposure
import com.depromeet.piki.profile.domain.ProfileRelation
import com.depromeet.piki.user.domain.User
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
    private val userBlockRepository: UserBlockRepository,
    private val friendshipRepository: FriendshipRepository,
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
        val ownerResult = ownerOf(query.viewerId, owner, wish)
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
        val shownTo = viewerId ?: NOBODY
        val card = itemCardService.cardOf(CardQuery(slot = wish.slot, itemId = itemId, owner = shownTo))
        val display = card.display ?: throw WishException.notFound()
        if (display.userInput && !display.isOwnedBy(shownTo)) throw WishException.notFound()
        return card
    }

    private fun ownerOf(
        viewerId: UUID?,
        owner: User,
        wish: Wish,
    ): ExploreOwnerResult? {
        val exposure =
            ProfileExposure.of(
                viewerId = viewerId,
                ownerId = owner.getId(),
                isOwnerProfilePublic = owner.isProfilePublic,
                isBlocked = isBlockedEitherWay(viewerId, owner.getId()),
            )
        // 그 사람의 공개 폴더를 볼 수 있는 사람이 곧 그 사람을 담은 사람으로 볼 수 있는 사람이다
        if (!exposure.canSeeFolder(isFolderPublic = true)) return null
        val folder = exploreWishJpaRepository.findPublicFoldersOf(wish.getId(), owner.getId()).firstOrNull()
        return ExploreOwnerResult(
            userId = owner.getId(),
            nickname = owner.nickname,
            profileImage = owner.profileImage,
            relation = relationOf(viewerId, owner.getId()),
            folder = folder?.let { ExploreFolderResult(folderId = it.getId(), name = it.name) },
        )
    }

    private fun isBlockedEitherWay(
        viewerId: UUID?,
        ownerId: UUID,
    ): Boolean {
        viewerId ?: return false
        return userBlockRepository.existsBetween(viewerId, ownerId)
    }

    private fun relationOf(
        viewerId: UUID?,
        ownerId: UUID,
    ): ProfileRelation {
        viewerId ?: return ProfileRelation.NOT_FRIEND
        if (viewerId == ownerId) return ProfileRelation.SELF
        if (friendshipRepository.exists(viewerId, ownerId)) return ProfileRelation.FRIEND
        return ProfileRelation.NOT_FRIEND
    }

    private fun isSavedBy(
        viewerId: UUID?,
        itemId: Long,
    ): Boolean {
        viewerId ?: return false
        return wishRepository.findByItemIdsAndUserId(listOf(itemId), viewerId).isNotEmpty()
    }

    companion object {
        // 비로그인으로 볼 때 누구의 입력도 자기 것으로 잡히지 않게 하는 자리 값
        private val NOBODY = UUID(0, 0)
    }
}
