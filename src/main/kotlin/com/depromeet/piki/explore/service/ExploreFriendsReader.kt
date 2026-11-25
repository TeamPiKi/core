package com.depromeet.piki.explore.service

import com.depromeet.piki.block.repository.UserBlockRepository
import com.depromeet.piki.explore.domain.ExploreCard
import com.depromeet.piki.explore.repository.ExploreWishJpaRepository
import com.depromeet.piki.explore.service.dto.ExploreFriendResult
import com.depromeet.piki.explore.service.dto.ExploreFriendWishResult
import com.depromeet.piki.explore.service.dto.ExploreFriendsResult
import com.depromeet.piki.explore.service.dto.GetExploreFriendsQuery
import com.depromeet.piki.friendship.repository.FriendshipRepository
import com.depromeet.piki.item.service.ItemCardService
import com.depromeet.piki.product.source.SourcePlatformResolver
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.service.UserService
import com.depromeet.piki.wishlist.repository.WishRepository
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// 보는 사람마다 목록이 달라 공통 피드에 넣을 수 없다. 친구 수가 적어 요청마다 DB 에서 읽는다
@Service
class ExploreFriendsReader(
    private val friendshipRepository: FriendshipRepository,
    private val userBlockRepository: UserBlockRepository,
    private val userService: UserService,
    private val wishRepository: WishRepository,
    private val exploreWishJpaRepository: ExploreWishJpaRepository,
    private val itemCardService: ItemCardService,
    private val sourcePlatformResolver: SourcePlatformResolver,
) {
    @Transactional(readOnly = true)
    fun getFriendWishes(query: GetExploreFriendsQuery): ExploreFriendsResult {
        val friends = visibleFriendsOf(query.viewerId)
        val ownerIds = selectedOwnerIds(friends, query.friendId)
        if (ownerIds.isEmpty()) return ExploreFriendsResult(friends.map { it.toResult() }, emptyList(), nextCursor = null, hasNext = false)

        val size = query.size.value
        val wishes =
            exploreWishJpaRepository.findLatestOfOwners(
                ownerIds = ownerIds,
                beforeWishId = query.cursor?.position ?: Long.MAX_VALUE,
                limit = Limit.of(size + 1),
            )
        val page = wishes.take(size)
        val hasNext = wishes.size > size
        val cards = itemCardService.cardsOf(page.map { it.cardQuery() })
        // 이름·가격·이미지가 덜 찬 카드는 내리지 않는다. 그만큼 한 쪽이 크기보다 적을 수 있다
        val exploreCards =
            page.mapNotNull { wish ->
                val card = cards.getValue(wish.cardQuery())
                ExploreCard.from(wish, card, sourcePlatformResolver.resolve(card.sourceLink))
            }
        val savedItemIds = savedItemIdsOf(query.viewerId, exploreCards.map { it.itemId })
        val friendById = friends.associateBy { it.getId() }
        return ExploreFriendsResult(
            friends = friends.map { it.toResult() },
            entries =
                exploreCards.map {
                    ExploreFriendWishResult(
                        card = it,
                        friend = friendById.getValue(it.ownerId).toResult(),
                        saved = it.itemId in savedItemIds,
                    )
                },
            nextCursor = page.lastOrNull()?.takeIf { hasNext }?.getId()?.toString(),
            hasNext = hasNext,
        )
    }

    // 프로필을 비공개로 바꾼 친구와 차단 관계가 된 친구는 뺀다. 최근에 위시를 담은 친구가 앞에 온다
    private fun visibleFriendsOf(viewerId: UUID): List<User> {
        val friendIds = friendshipRepository.findAllByUserId(viewerId).map { it.friendId }
        if (friendIds.isEmpty()) return emptyList()
        val blocked = userBlockRepository.findCounterpartIds(viewerId)
        val visible = userService.findActiveByIds(friendIds).filter { it.isProfilePublic && it.getId() !in blocked }
        if (visible.isEmpty()) return emptyList()
        val latestWishId =
            exploreWishJpaRepository
                .findLatestWishIdByOwner(visible.map { it.getId() })
                .associate { it.userId to it.wishId }
        return visible.sortedByDescending { latestWishId[it.getId()] ?: 0L }
    }

    // 친구가 아닌 사람을 고르면 빈 목록이다. 방금 친구에서 빠진 사람의 칩을 눌러도 오류가 아니다
    private fun selectedOwnerIds(
        friends: List<User>,
        friendId: UUID?,
    ): List<UUID> {
        val friendIds = friends.map { it.getId() }
        friendId ?: return friendIds
        return friendIds.filter { it == friendId }
    }

    private fun savedItemIdsOf(
        viewerId: UUID,
        itemIds: List<Long>,
    ): Set<Long> {
        if (itemIds.isEmpty()) return emptySet()
        return wishRepository
            .findByItemIdsAndUserId(itemIds, viewerId)
            .mapNotNull { it.itemId }
            .toSet()
    }

    private fun User.toResult(): ExploreFriendResult = ExploreFriendResult(userId = getId(), nickname = nickname, profileImage = profileImage)
}
