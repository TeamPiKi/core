package com.depromeet.piki.notification.sse

import com.depromeet.piki.common.config.AsyncConfig
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.domain.ItemSlotType
import com.depromeet.piki.item.event.ItemParsingCompleted
import com.depromeet.piki.item.event.ItemParsingFailed
import com.depromeet.piki.item.event.ItemParsingIncomplete
import com.depromeet.piki.notification.controller.dto.TournamentItemParsed
import com.depromeet.piki.tournament.repository.TournamentItemRepository
import com.depromeet.piki.tournament.repository.TournamentUserRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

// 토너먼트 출전 아이템의 파싱 완료/실패를 그 토너먼트 참여자 화면에 실시간 반영한다.
//
// 문제: 주최자가 링크/이미지로 아이템을 추가하면 참여자는 TOURNAMENT_ITEM_ADDED 로 PARSING 카드(로딩바)를 띄우지만,
// 파싱이 끝나도 별도 신호가 없어 새로고침 전까지 로딩이 멈추지 않았다. 파싱 완료/실패 알림(ITEM_PARSING_*)은
// 올린 본인(adder)·위시 주인에게만 가고(노이즈 방지, ItemParsingRecipientResolver) 다른 참여자에겐 가지 않기 때문.
//
// 해결: 출전 카드가 요청한 파싱이 끝나면(READY·INCOMPLETE·FAILED) 그 토너먼트의 참여자 전원에게 SSE 조용한 갱신 이벤트
// (silent-sync, type=TOURNAMENT_ITEM_PARSED)를 보내 카드를 갱신하게 한다. 이건 "알림"이 아니라 화면 갱신 신호라
// 알림센터·FCM 을 거치지 않고 SSE 로만 흐른다(NotificationDispatcher 경로와 별개 — 추가 알림 노이즈를 만들지 않는다).
//
// 결합 방향: 알림 -> 도메인 (단방향). item·tournament 도메인은 이 클래스를 모른다(자기 이벤트만 발행).
// AFTER_COMMIT + @Async: 파싱 상태 전이가 커밋된 뒤에만(롤백 시 미발송) 워커 스레드와 분리해 전달한다
// (notification 의 NotificationEventListener 와 같은 결, 같은 executor).
@Component
class TournamentItemParsedSseBroadcaster(
    private val tournamentItemRepository: TournamentItemRepository,
    private val tournamentUserRepository: TournamentUserRepository,
    private val localDelivery: LocalSseDelivery,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async(AsyncConfig.NOTIFICATION_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun on(event: ItemParsingCompleted) {
        broadcast(event.slot, ItemStatus.READY)
    }

    // 일부만 채워 끝난 경우도 카드 갱신 대상이다 — 참여자 화면의 로딩바를 멈추게 해야 하는 건 완료·실패와 같고,
    // 카드가 "나머지를 채워 주세요" 상태로 바뀌어야 하기 때문이다(#944). status 를 그대로 실어 보내므로
    // 클라이언트는 이미 쓰던 필드로 새 값 하나를 더 받는다.
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

    // 요청한 출전 카드의 토너먼트 참여자 전원에게 보낸다. 위시 요청이면 보낼 곳이 없다(위시 주인은 알림으로 받음)
    // @Async 워커라 throw 를 삼키지 않으면 맥락 없는 스택트레이스만 남아 동기화 누락이 무음이 된다
    fun broadcast(
        slot: ItemSlot,
        status: ItemStatus,
    ) {
        if (slot.type != ItemSlotType.TOURNAMENT_ITEM) return
        runCatching {
            val tournamentItem = tournamentItemRepository.findByIdIncludingDeleted(slot.id) ?: return
            val participants = tournamentUserRepository.findByTournamentId(tournamentItem.tournamentId).map { it.userId }
            localDelivery.deliverSilentSync(
                participants,
                TournamentItemParsed(
                    tournamentId = tournamentItem.tournamentId,
                    tournamentItemId = tournamentItem.getId(),
                    status = status,
                ),
            )
            log.info(
                "토너먼트 아이템 파싱 동기화 전송 tournamentId={} tournamentItemId={} status={} 참여자={}명",
                tournamentItem.tournamentId,
                tournamentItem.getId(),
                status,
                participants.size,
            )
        }.onFailure { e ->
            log.warn("토너먼트 아이템 파싱 동기화 실패 slot={} status={}", slot, status, e)
        }
    }
}
