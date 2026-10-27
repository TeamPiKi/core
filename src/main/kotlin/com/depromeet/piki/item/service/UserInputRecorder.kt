package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.UserInputValues
import com.depromeet.piki.item.repository.ItemParseOutboxRepository
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class UserInputRecorder(
    private val itemRepository: ItemRepository,
    private val itemSnapshotRepository: ItemSnapshotRepository,
    private val itemParseOutboxRepository: ItemParseOutboxRepository,
    private val itemResolver: ItemResolver,
    private val itemSlots: ItemSlots,
) {
    // 아이템 행을 잠가 토너먼트 시작과 버전 생성을 직렬화한다
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(
        slot: ItemSlot,
        itemId: Long?,
        values: UserInputValues,
        createdBy: UUID,
    ): ItemSlotFill {
        val resolvedId = itemId ?: requestedItemIdOf(slot)
        val item = itemRepository.findByIdForUpdate(resolvedId) ?: error("item $resolvedId 가 없다")
        itemSnapshotRepository.save(ItemSnapshot.userInput(item.getId(), values, createdBy))
        return itemSlots.fill(slot, item.getId())
    }

    private fun requestedItemIdOf(slot: ItemSlot): Long {
        val latest = itemParseOutboxRepository.findLatestBySlot(slot) ?: error("슬롯 ${slot.type} ${slot.id} 의 요청이 없다")
        return itemResolver.resolve(latest.input, finalUrl = null).getId()
    }
}
