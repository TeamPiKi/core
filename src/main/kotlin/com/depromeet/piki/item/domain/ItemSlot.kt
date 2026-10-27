package com.depromeet.piki.item.domain

data class ItemSlot(
    val type: ItemSlotType,
    val id: Long,
) {
    init {
        require(id > 0) { "슬롯 id 는 양수여야 한다: $id" }
    }

    companion object {
        fun wish(wishId: Long): ItemSlot = ItemSlot(ItemSlotType.WISH, wishId)

        fun tournamentItem(tournamentItemId: Long): ItemSlot = ItemSlot(ItemSlotType.TOURNAMENT_ITEM, tournamentItemId)
    }
}
