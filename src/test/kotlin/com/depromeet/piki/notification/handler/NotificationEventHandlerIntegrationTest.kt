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

    // 파싱 완료 문구는 등록 출처(위시/토너먼트)로 갈리지 않는다 — 갈리면 안 되는 이유가 구조에 있다.
    // dispatcher 는 문구·라우팅을 수신자 루프 **밖에서 한 번** 해석해 전 수신자에게 같은 값을 박는데, 공유(#825)의
    // "진행 중 합류" 로 한 snapshot 에 위시 주인과 토너먼트 등록자가 함께 붙을 수 있다. 그 상태로 출처별 문구를 쓰면
    // 위시 주인이 토너먼트 문구를 받는다. 출처별 분기는 수신자별 라우팅 해석과 함께 후속(#933)에서 한다.
    //
    // 그래서 "출전 pin 이 있어도 문구 변수가 늘지 않는다" 를 못 박는다 — 수신자별 해석 없이 분기를 되살리면 여기서 깨진다.
    @Test
    fun `파싱 완료 문구 변수는 출전 pin 이 있어도 itemName 하나뿐이다`() {
        val snapshotId = itemSnapshotRepository.save(ItemSnapshot(itemId = 9103L, name = "나이키 에어맥스")).getId()
        // 이 버전을 pin 한 출전이 있으면 라우팅은 Tournament 로 해석된다. 그래도 문구는 그대로여야 한다.
        tournamentItemJpaRepository.save(TournamentItem(tournamentId = 9201L, userId = UUID.randomUUID(), snapshotId = snapshotId))

        val context = itemParsingCompletedHandler.resolveActorContext(ItemParsingCompleted(itemId = 9103L, snapshotId = snapshotId))

        assertEquals(setOf("itemName"), context.variables.keys)
        assertEquals("나이키 에어맥스", context.variables["itemName"])
    }

    // 파싱 실패 알림도 완료·미완과 동일하게 title 변수 itemName 을 그 버전 이름에서 채운다(#959).
    // 실패 템플릿 문구는 현재 고정이라 렌더엔 안 쓰이지만, 어드민이 실패 문구에 이름을 넣어 편집할 수 있게 dispatch 가 채워 둔다.
    @Test
    fun `파싱 실패 핸들러는 그 버전의 아이템 이름을 itemName 변수로 담는다`() {
        val snapshotId = itemSnapshotRepository.save(ItemSnapshot(itemId = 9111L, name = "나이키 에어맥스")).getId()

        val context = itemParsingFailedHandler.resolveActorContext(ItemParsingFailed(itemId = 9111L, snapshotId = snapshotId))

        assertEquals("나이키 에어맥스", context.variables["itemName"])
    }

    // 추출 자체가 실패해 이름이 비는 게 오히려 흔한 경로 — 그때도 이름 하나 때문에 알림을 떨구지 않고 기본값을 담는다.
    @Test
    fun `파싱 실패 핸들러는 버전을 못 찾으면 itemName 을 기본값으로 담는다`() {
        val context = itemParsingFailedHandler.resolveActorContext(ItemParsingFailed(itemId = 9112L, snapshotId = 9_999_998L))

        assertEquals("상품", context.variables["itemName"])
    }
}
