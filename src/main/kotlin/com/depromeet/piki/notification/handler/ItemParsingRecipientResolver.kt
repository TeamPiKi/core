package com.depromeet.piki.notification.handler

import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.domain.ItemSlotType
import com.depromeet.piki.item.service.ItemCardService
import com.depromeet.piki.notification.domain.NotificationRouting
import com.depromeet.piki.tournament.repository.TournamentItemCardView
import com.depromeet.piki.tournament.repository.TournamentItemRepository
import com.depromeet.piki.wishlist.repository.WishRepository
import org.springframework.stereotype.Component
import java.util.UUID

// 파싱 알림은 요청한 카드의 주인에게 그 카드 좌표로 간다. 해소 통지만 같은 상품의 다른 카드로 간다
@Component
class ItemParsingRecipientResolver(
    private val wishRepository: WishRepository,
    private val tournamentItemRepository: TournamentItemRepository,
    private val itemCardService: ItemCardService,
) {
    fun routingOf(slot: ItemSlot): NotificationRouting =
        when (slot.type) {
            ItemSlotType.WISH -> NotificationRouting.Wish(slot.id)
            ItemSlotType.TOURNAMENT_ITEM -> tournamentRoutingOf(slot)
        }

    // 토너먼트 아이템 행은 하드 삭제하지 않아 지운 행까지 보면 늘 있다
    fun tournamentRoutingOf(slot: ItemSlot): NotificationRouting.Tournament {
        require(slot.type == ItemSlotType.TOURNAMENT_ITEM) { "$slot 는 토너먼트 아이템이 아니다" }
        val tournamentItem = tournamentItemRepository.findByIdIncludingDeleted(slot.id) ?: error("토너먼트 아이템 ${slot.id} 이 없다")
        return NotificationRouting.Tournament(tournamentItem.tournamentId, tournamentItem.getId())
    }

    // 해소 통지(#1028). 이 버전 직전엔 미완이던 카드가 이 버전으로 채워지면 그 주인에게 간다. 요청한 카드는 완료 알림을 받아 뺀다
    fun resolveRecoveredRoutings(
        itemId: Long,
        snapshotId: Long,
        slot: ItemSlot,
    ): Map<UUID, NotificationRouting> {
        val versions = itemCardService.versionsOf(itemId)
        val wishIdByUser =
            wishRepository
                .findCardsByItemId(itemId)
                .filterNot { ItemSlot.wish(it.wishId) == slot }
                .filter { versions.recovers(viewer = it.userId, snapshotId = snapshotId) }
                .associate { it.userId to it.wishId }
        val tournamentCards =
            tournamentItemRepository
                .findPendingCardsByItemId(itemId)
                .filterNot { ItemSlot.tournamentItem(it.tournamentItemId) == slot }
                .filter { versions.recovers(viewer = it.userId, snapshotId = snapshotId) }
        return routingsOf(wishIdByUser, tournamentCards)
    }

    // 한 유저가 양쪽이면 WISH 우선. 같은 유저의 토너먼트 좌표가 여럿이면 id 오름차순 첫 행으로 결정성만 확보한다
    private fun routingsOf(
        wishIdByUser: Map<UUID, Long>,
        tournamentCards: List<TournamentItemCardView>,
    ): Map<UUID, NotificationRouting> {
        val tournamentByUser = tournamentCards.groupBy { it.userId }.mapValues { (_, rows) -> rows.first() }
        return (wishIdByUser.keys + tournamentByUser.keys).associateWith { userId ->
            wishIdByUser[userId]?.let { NotificationRouting.Wish(it) }
                ?: tournamentByUser.getValue(userId).let { NotificationRouting.Tournament(it.tournamentId, it.tournamentItemId) }
        }
    }
}
