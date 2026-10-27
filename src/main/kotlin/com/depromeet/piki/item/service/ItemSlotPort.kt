package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.domain.ItemSlotType
import com.depromeet.piki.item.event.ItemSlotMerged
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.UUID

// 슬롯(위시·토너먼트 아이템)을 소유한 도메인이 구현한다. item 도메인은 그 도메인을 모른다
interface ItemSlotPort {
    val type: ItemSlotType

    // soft delete 된 슬롯도 Present 다. 슬롯이 지워져도 파싱 결과는 아이템에 남는다
    fun find(id: Long): ItemSlotState

    // 같은 상품임을 요청 시점에 알면 등록 경로가 409 로 막고, 아이템이 확정될 때에야 알면 여기서 남은 슬롯에 합친다
    // 지운 슬롯과 이미 아이템이 있는 슬롯은 합치지 않는다
    fun fill(
        id: Long,
        itemId: Long,
    ): ItemSlotFill
}

sealed interface ItemSlotState {
    data class Present(
        val owner: UUID,
        val itemId: Long?,
    ) : ItemSlotState

    // 탈퇴로 행이 하드 삭제됨
    data object Gone : ItemSlotState
}

sealed interface ItemSlotFill {
    data object Filled : ItemSlotFill

    data class AlreadyHeld(
        val holder: ItemSlot,
        val owner: UUID,
        val holderOwner: UUID,
    ) : ItemSlotFill
}

@Component
class ItemSlots(
    ports: List<ItemSlotPort>,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val portByType = ports.associateBy { it.type }

    fun find(slot: ItemSlot): ItemSlotState = portOf(slot).find(slot.id)

    // 파싱 성공과 사용자 입력 어느 쪽으로 합쳐져도 합침 사실이 빠지지 않게 여기서 발행한다
    fun fill(
        slot: ItemSlot,
        itemId: Long,
    ): ItemSlotFill {
        val fill = portOf(slot).fill(slot.id, itemId)
        when (fill) {
            ItemSlotFill.Filled -> Unit
            is ItemSlotFill.AlreadyHeld -> eventPublisher.publishEvent(ItemSlotMerged(slot, fill.holder, fill.owner, fill.holderOwner, itemId))
        }
        return fill
    }

    private fun portOf(slot: ItemSlot): ItemSlotPort =
        portByType[slot.type] ?: error("슬롯 타입 ${slot.type} 을 다루는 도메인이 없다")
}
