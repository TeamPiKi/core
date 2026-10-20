package com.depromeet.piki.item.event

import com.depromeet.piki.common.event.NotificationEvent
import com.depromeet.piki.item.domain.ItemSlot
import java.util.UUID

data class ItemParsingFailed(
    val slot: ItemSlot,
    val owner: UUID,
    val refresh: Boolean,
) : NotificationEvent
