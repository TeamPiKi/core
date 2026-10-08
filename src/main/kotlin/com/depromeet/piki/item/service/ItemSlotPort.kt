package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.domain.ItemSlotType
import org.springframework.stereotype.Component
import java.util.UUID

// 슬롯(위시·토너먼트 아이템)을 소유한 도메인이 구현한다. item 도메인은 그 도메인을 모른다
interface ItemSlotPort {
    val type: ItemSlotType

    // 지워진 슬롯도 돌려준다. 슬롯이 지워져도 파싱 결과는 아이템에 남는다
    fun find(id: Long): ItemSlotState?

    fun fill(
        id: Long,
        itemId: Long,
    )
}

data class ItemSlotState(
    val owner: UUID,
    val itemId: Long?,
)

@Component
class ItemSlots(
    ports: List<ItemSlotPort>,
) {
    private val portByType = ports.associateBy { it.type }

    fun find(slot: ItemSlot): ItemSlotState? = portOf(slot).find(slot.id)

    fun fill(
        slot: ItemSlot,
        itemId: Long,
    ) {
        portOf(slot).fill(slot.id, itemId)
    }

    private fun portOf(slot: ItemSlot): ItemSlotPort =
        portByType[slot.type] ?: error("슬롯 타입 ${slot.type} 을 다루는 도메인이 없다")
}
