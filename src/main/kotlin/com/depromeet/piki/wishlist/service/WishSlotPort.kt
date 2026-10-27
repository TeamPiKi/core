package com.depromeet.piki.wishlist.service

import com.depromeet.piki.item.domain.ItemSlotType
import com.depromeet.piki.item.service.ItemSlotFill
import com.depromeet.piki.item.service.ItemSlotPort
import com.depromeet.piki.item.service.ItemSlotState
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.repository.WishMemoRepository
import com.depromeet.piki.wishlist.repository.WishRepository
import org.springframework.stereotype.Component

@Component
class WishSlotPort(
    private val wishRepository: WishRepository,
    private val wishMemoRepository: WishMemoRepository,
) : ItemSlotPort {
    override val type: ItemSlotType = ItemSlotType.WISH

    override fun find(id: Long): ItemSlotState =
        wishRepository.findByIdIncludingDeletedForUpdate(id)?.let { ItemSlotState.Present(owner = it.userId, itemId = it.itemId) } ?: ItemSlotState.Gone

    override fun fill(
        id: Long,
        itemId: Long,
    ): ItemSlotFill {
        val wish = wishRepository.findByIdIncludingDeletedForUpdate(id) ?: error("위시 $id 이 없다")
        val holder =
            holderOf(wish, itemId) ?: run {
                wish.fill(itemId)
                return ItemSlotFill.Filled
            }
        wishMemoRepository.saveAll(wishMemoRepository.findAllByWishId(wish.getId()).map { it.copyTo(holder.getId()) })
        wish.delete()
        return ItemSlotFill.AlreadyHeld(holder.slot, wish.userId, holder.userId)
    }

    private fun holderOf(
        wish: Wish,
        itemId: Long,
    ): Wish? {
        wish.deletedAt?.let { return null }
        wish.itemId?.let { return null }
        return wishRepository.findByItemIdsAndUserId(listOf(itemId), wish.userId).firstOrNull()
    }
}
