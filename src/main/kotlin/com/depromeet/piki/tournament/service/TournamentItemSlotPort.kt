package com.depromeet.piki.tournament.service

import com.depromeet.piki.item.domain.ItemSlotType
import com.depromeet.piki.item.service.ItemSlotPort
import com.depromeet.piki.item.service.ItemSlotState
import com.depromeet.piki.tournament.repository.TournamentItemRepository
import org.springframework.stereotype.Component

@Component
class TournamentItemSlotPort(
    private val tournamentItemRepository: TournamentItemRepository,
) : ItemSlotPort {
    override val type: ItemSlotType = ItemSlotType.TOURNAMENT_ITEM

    override fun find(id: Long): ItemSlotState =
        tournamentItemRepository.findByIdIncludingDeletedForUpdate(id)?.let { ItemSlotState.Present(owner = it.userId, itemId = it.itemId) } ?: ItemSlotState.Gone

    override fun fill(
        id: Long,
        itemId: Long,
    ) {
        val tournamentItem = tournamentItemRepository.findByIdIncludingDeletedForUpdate(id) ?: error("토너먼트 아이템 $id 이 없다")
        tournamentItem.fill(itemId)
    }
}
