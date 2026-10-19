package com.depromeet.piki.tournament.service

import com.depromeet.piki.item.domain.ItemSlotType
import com.depromeet.piki.item.service.ItemSlotFill
import com.depromeet.piki.item.service.ItemSlotPort
import com.depromeet.piki.item.service.ItemSlotState
import com.depromeet.piki.tournament.domain.TournamentItem
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
    ): ItemSlotFill {
        val tournamentItem = tournamentItemRepository.findByIdIncludingDeletedForUpdate(id) ?: error("토너먼트 아이템 $id 이 없다")
        val holder =
            holderOf(tournamentItem, itemId) ?: run {
                tournamentItem.fill(itemId)
                return ItemSlotFill.Filled
            }
        tournamentItem.softDelete()
        return ItemSlotFill.AlreadyHeld(holder.slot, tournamentItem.userId, holder.userId)
    }

    private fun holderOf(
        tournamentItem: TournamentItem,
        itemId: Long,
    ): TournamentItem? {
        tournamentItem.deletedAt?.let { return null }
        tournamentItem.itemId?.let { return null }
        return tournamentItemRepository.findByTournamentIdAndItemId(tournamentItem.tournamentId, itemId)
    }
}
