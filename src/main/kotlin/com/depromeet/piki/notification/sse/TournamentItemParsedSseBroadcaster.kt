package com.depromeet.piki.notification.sse

import com.depromeet.piki.common.config.AsyncConfig
import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.domain.ItemSlotType
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.event.ItemParsingCompleted
import com.depromeet.piki.item.event.ItemParsingFailed
import com.depromeet.piki.item.event.ItemParsingIncomplete
import com.depromeet.piki.item.event.ItemSlotMerged
import com.depromeet.piki.notification.controller.dto.SilentSyncPayload
import com.depromeet.piki.notification.controller.dto.TournamentItemMerged
import com.depromeet.piki.notification.controller.dto.TournamentItemParsed
import com.depromeet.piki.notification.domain.NotificationRouting
import com.depromeet.piki.notification.handler.ItemParsingRecipientResolver
import com.depromeet.piki.tournament.repository.TournamentUserRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

// 파싱 알림은 요청한 카드 주인에게만 간다. 같은 토너먼트의 다른 참여자는 이 조용한 갱신을 받아야 카드 로딩이 멈춘다
@Component
class TournamentItemParsedSseBroadcaster(
    private val recipientResolver: ItemParsingRecipientResolver,
    private val tournamentUserRepository: TournamentUserRepository,
    private val localDelivery: LocalSseDelivery,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async(AsyncConfig.NOTIFICATION_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun on(event: ItemParsingCompleted) {
        broadcast(event.slot, ItemStatus.READY)
    }

    @Async(AsyncConfig.NOTIFICATION_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun on(event: ItemParsingIncomplete) {
        broadcast(event.slot, ItemStatus.INCOMPLETE)
    }

    @Async(AsyncConfig.NOTIFICATION_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun on(event: ItemParsingFailed) {
        broadcast(event.slot, ItemStatus.FAILED)
    }

    @Async(AsyncConfig.NOTIFICATION_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun on(event: ItemSlotMerged) {
        broadcastMerged(event)
    }

    fun broadcast(
        slot: ItemSlot,
        status: ItemStatus,
    ) {
        deliver(slot, "status=$status") { TournamentItemParsed(it.tournamentId, it.tournamentItemId, status) }
    }

    // 위시끼리 합쳐지면 토너먼트 좌표가 없어 보내지 않는다
    fun broadcastMerged(merged: ItemSlotMerged) {
        deliver(merged.holder, "merged=${merged.slot.id}") { TournamentItemMerged(it.tournamentId, merged.slot.id, it.tournamentItemId) }
    }

    // @Async 워커라 throw 를 삼키지 않으면 맥락 없는 스택트레이스만 남아 동기화 누락이 무음이 된다
    private fun deliver(
        slot: ItemSlot,
        detail: String,
        payloadOf: (NotificationRouting.Tournament) -> SilentSyncPayload,
    ) {
        if (slot.type != ItemSlotType.TOURNAMENT_ITEM) return
        runCatching {
            val tournament = recipientResolver.tournamentRoutingOf(slot)
            val participants = tournamentUserRepository.findByTournamentId(tournament.tournamentId).map { it.userId }
            localDelivery.deliverSilentSync(participants, payloadOf(tournament))
            log.info(
                "토너먼트 아이템 파싱 동기화 전송 tournamentId={} tournamentItemId={} {} 참여자={}명",
                tournament.tournamentId,
                tournament.tournamentItemId,
                detail,
                participants.size,
            )
        }.onFailure { e ->
            log.warn("토너먼트 아이템 파싱 동기화 실패 slotType={} slotId={} {}", slot.type, slot.id, detail, e)
        }
    }
}
