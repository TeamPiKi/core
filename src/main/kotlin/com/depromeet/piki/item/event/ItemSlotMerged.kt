package com.depromeet.piki.item.event

import com.depromeet.piki.item.domain.ItemSlot
import java.util.UUID

data class ItemSlotMerged(
    val slot: ItemSlot,
    val holder: ItemSlot,
    val owner: UUID,
)
