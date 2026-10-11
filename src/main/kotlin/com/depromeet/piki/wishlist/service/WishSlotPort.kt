package com.depromeet.piki.wishlist.service

import com.depromeet.piki.item.domain.ItemSlotType
import com.depromeet.piki.item.service.ItemSlotPort
import com.depromeet.piki.item.service.ItemSlotState
import com.depromeet.piki.wishlist.repository.WishRepository
import org.springframework.stereotype.Component

@Component
class WishSlotPort(
    private val wishRepository: WishRepository,
) : ItemSlotPort {
    override val type: ItemSlotType = ItemSlotType.WISH

    override fun find(id: Long): ItemSlotState =
        wishRepository.findByIdIncludingDeletedForUpdate(id)?.let { ItemSlotState.Present(owner = it.userId, itemId = it.itemId) } ?: ItemSlotState.Gone

    override fun fill(
        id: Long,
        itemId: Long,
    ) {
        val wish = wishRepository.findByIdIncludingDeletedForUpdate(id) ?: error("위시 $id 이 없다")
        wish.fill(itemId)
    }
}
