package com.depromeet.piki.wishlist.repository

import com.depromeet.piki.image.domain.ProductImageKey
import com.depromeet.piki.item.domain.CanonicalInput
import com.depromeet.piki.item.domain.NormalizedInput
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.domain.WishCursor
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class WishRepositoryImpl(
    private val wishJpaRepository: WishJpaRepository,
) : WishRepository {
    override fun save(wish: Wish): Wish = wishJpaRepository.save(wish)

    override fun hardDeleteAllByUserId(userId: UUID): Int = wishJpaRepository.hardDeleteAllByUserId(userId)

    override fun findPage(
        userId: UUID,
        cursor: WishCursor?,
        limit: Int,
    ): List<Wish> {
        val limited = Limit.of(limit)
        cursor ?: return wishJpaRepository.findByUserIdAndDeletedAtIsNullOrderByIdDesc(userId, limited)
        return wishJpaRepository.findByUserIdAndIdLessThanAndDeletedAtIsNullOrderByIdDesc(
            userId,
            cursor.lastWishId,
            limited,
        )
    }

    override fun findById(id: Long): Wish? = wishJpaRepository.findByIdAndDeletedAtIsNull(id)

    override fun findByIdForUpdate(id: Long): Wish? = wishJpaRepository.findByIdForUpdate(id)

    override fun findAllByIds(ids: List<Long>): List<Wish> = wishJpaRepository.findByIdInAndDeletedAtIsNull(ids)

    override fun findByItemIdsAndUserId(
        itemIds: List<Long>,
        userId: UUID,
    ): List<Wish> = wishJpaRepository.findByItemIdInAndUserIdAndDeletedAtIsNull(itemIds, userId)

    override fun findCardsByItemId(itemId: Long): List<WishCardView> = wishJpaRepository.findCardsByItemId(itemId)

    override fun findByIdIncludingDeletedForUpdate(id: Long): Wish? = wishJpaRepository.findByIdIncludingDeletedForUpdate(id)

    override fun findIdByUserIdAndNormalizedInput(
        userId: UUID,
        normalized: NormalizedInput,
    ): Long? = wishJpaRepository.findIdsByUserIdAndNormalizedInput(userId, normalized).firstOrNull()

    override fun findIdByUserIdAndItemCanonical(
        userId: UUID,
        canonical: CanonicalInput,
    ): Long? = wishJpaRepository.findIdsByUserIdAndItemCanonical(userId, canonical).firstOrNull()

    override fun findRequestedImageKeys(
        userId: UUID,
        keys: Collection<ProductImageKey>,
    ): Set<ProductImageKey> {
        if (keys.isEmpty()) return emptySet()
        return wishJpaRepository
            .findRequestedInputsByUserId(userId, keys.map { it.value })
            .map(ProductImageKey::parse)
            .toSet()
    }
}
