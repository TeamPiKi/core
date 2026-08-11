package com.depromeet.piki.notification.handler

import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.event.ItemParsingCompleted
import com.depromeet.piki.item.event.ItemParsingFailed
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.notification.domain.NotificationType
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.tournament.domain.TournamentItem
import com.depromeet.piki.tournament.repository.TournamentItemJpaRepository
import com.depromeet.piki.tournament.event.TournamentItemAdded
import com.depromeet.piki.tournament.event.TournamentItemDeleted
import com.depromeet.piki.tournament.event.TournamentJoined
import com.depromeet.piki.tournament.event.TournamentStarted
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.assertEquals

// 핸들러가 이제 repo·resolver 를 주입받으므로 무인자 생성이 불가하다. 실제 와이어링된 빈을 autowire 해
// 베이스 클래스의 제네릭 eventType 도출·notificationType·유일성을 검증한다(내부 모킹 없이 실제 빈으로).
//
// @Transactional 자동 롤백 — itemName 검증이 실제 snapshot 행을 쓰는데, 롤백이 없으면 items 행 없는
// PROCESSING snapshot 이 공유 DB 에 남는다. 그걸 살아있는 ItemParsingScheduler.recover() 가 stale 로
// 집어 markFailed·ItemParsingFailed 발행·log.error 를 내고, 정원 배치 스캔에도 유령 행으로 섞인다.
@Transactional
class NotificationEventHandlerIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var itemParsingCompletedHandler: ItemParsingCompletedHandler

    @Autowired private lateinit var itemParsingFailedHandler: ItemParsingFailedHandler

    @Autowired private lateinit var tournamentItemAddedHandler: TournamentItemAddedHandler

    @Autowired private lateinit var tournamentItemDeletedHandler: TournamentItemDeletedHandler

    @Autowired private lateinit var tournamentJoinedHandler: TournamentJoinedHandler

    @Autowired private lateinit var tournamentStartedHandler: TournamentStartedHandler

    @Autowired private lateinit var handlers: List<NotificationEventHandler<*>>

    @Autowired private lateinit var itemSnapshotRepository: ItemSnapshotRepository

    @Autowired private lateinit var tournamentItemJpaRepository: TournamentItemJpaRepository

    // eventType 은 제네릭 타입 인자 E 에서 GenericTypeResolver 로 자동 도출된다(::class 명시 제거).
    // reflection 기반이라 클래스 계층이 바뀌면 조용히 틀어질 수 있어, 도출 결과를 직접 못 박아 회귀를 잡는다.
    @Test
    fun `각 핸들러의 eventType 이 제네릭 인자에서 올바르게 도출된다`() {
        assertEquals(ItemParsingCompleted::class, itemParsingCompletedHandler.eventType)
        assertEquals(ItemParsingFailed::class, itemParsingFailedHandler.eventType)
        assertEquals(TournamentItemAdded::class, tournamentItemAddedHandler.eventType)
        assertEquals(TournamentItemDeleted::class, tournamentItemDeletedHandler.eventType)
        assertEquals(TournamentJoined::class, tournamentJoinedHandler.eventType)
        assertEquals(TournamentStarted::class, tournamentStartedHandler.eventType)
    }

    @Test
    fun `notificationType 이 생성자로 주입돼 핸들러와 짝이 맞는다`() {
        assertEquals(NotificationType.ITEM_PARSING_COMPLETED, itemParsingCompletedHandler.notificationType)
        assertEquals(NotificationType.ITEM_PARSING_FAILED, itemParsingFailedHandler.notificationType)
        assertEquals(NotificationType.TOURNAMENT_ITEM_ADDED, tournamentItemAddedHandler.notificationType)
        assertEquals(NotificationType.TOURNAMENT_ITEM_DELETED, tournamentItemDeletedHandler.notificationType)
        assertEquals(NotificationType.TOURNAMENT_JOINED, tournamentJoinedHandler.notificationType)
        assertEquals(NotificationType.TOURNAMENT_STARTED, tournamentStartedHandler.notificationType)
    }

    // Dispatcher 는 eventType 으로 라우팅하므로 키가 유일해야 한다(중복이면 associateBy 가 조용히 덮어쓴다).
    // 등록된 모든 핸들러 빈을 받아 검사하므로, 새 핸들러가 같은 eventType 으로 끼면 여기서 잡힌다.
    @Test
    fun `모든 핸들러의 eventType 은 서로 겹치지 않는다`() {
        val eventTypes = handlers.map { it.eventType }
        assertEquals(eventTypes.size, eventTypes.toSet().size)
    }

    // 파싱 완료 알림 문구에 담을 itemName 변수를 그 버전(snapshot)의 이름에서 채운다(#895).
    @Test
    fun `파싱 완료 핸들러는 그 버전의 아이템 이름을 itemName 변수로 담는다`() {
        val snapshotId = itemSnapshotRepository.save(ItemSnapshot(itemId = 9101L, name = "나이키 에어맥스")).getId()

        val context = itemParsingCompletedHandler.resolveActorContext(ItemParsingCompleted(itemId = 9101L, snapshotId = snapshotId))

        assertEquals("나이키 에어맥스", context.variables["itemName"])
    }

    // best-effort — 버전이 없어도(이례) 이름 하나 때문에 알림을 떨구지 않고 기본값을 담는다.
    @Test
    fun `파싱 완료 핸들러는 버전을 못 찾으면 itemName 을 기본값으로 담는다`() {
        val context = itemParsingCompletedHandler.resolveActorContext(ItemParsingCompleted(itemId = 9102L, snapshotId = 9_999_999L))

        assertEquals("상품", context.variables["itemName"])
    }

    // body 문구는 등록 출처로 갈린다(#913) — 토너먼트에 올린 상품이 위시리스트에 들어가지는 않으므로 같은 문구를 쓸 수 없다.
    // 출처 판정은 라우팅(그 버전을 pin 한 출전이 있나)이 이미 하고 있고, 그 결과를 그대로 재사용한다.
    @Test
    fun `위시 출처 파싱 완료는 completionMessage 를 위시 문구로 담는다`() {
        val snapshotId = itemSnapshotRepository.save(ItemSnapshot(itemId = 9103L, name = "나이키 에어맥스")).getId()

        val context = itemParsingCompletedHandler.resolveActorContext(ItemParsingCompleted(itemId = 9103L, snapshotId = snapshotId))

        assertEquals(ItemParsingCompletedHandler.WISH_MESSAGE, context.variables["completionMessage"])
    }

    @Test
    fun `토너먼트 출처 파싱 완료는 completionMessage 를 토너먼트 문구로 담는다`() {
        val snapshotId = itemSnapshotRepository.save(ItemSnapshot(itemId = 9104L, name = "나이키 덩크")).getId()
        // 그 버전을 pin 한 출전이 있으면 라우팅이 Tournament 로 해석된다.
        tournamentItemJpaRepository.save(TournamentItem(tournamentId = 9201L, userId = UUID.randomUUID(), snapshotId = snapshotId))

        val context = itemParsingCompletedHandler.resolveActorContext(ItemParsingCompleted(itemId = 9104L, snapshotId = snapshotId))

        assertEquals(ItemParsingCompletedHandler.TOURNAMENT_MESSAGE, context.variables["completionMessage"])
    }
}
