package com.depromeet.piki.notification.sse

import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.notification.controller.dto.TournamentItemParsed
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.tournament.domain.TournamentItem
import com.depromeet.piki.tournament.domain.TournamentUser
import com.depromeet.piki.tournament.repository.TournamentItemRepository
import com.depromeet.piki.tournament.repository.TournamentUserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 토너먼트 출전 아이템의 파싱 완료/실패 → 참여자 SSE 조용한 갱신(silent-sync, type=TOURNAMENT_ITEM_PARSED)을 실제 빈으로 검증한다.
// 라우팅 키는 snapshotId(버전)다(#576) — 브로드캐스터의 수신자·좌표 도출은 DB 역조회(tournament_item pin, tournament_user)에 의존하므로 통합으로 검증한다.
// 영속 fixture 는 @Transactional 자동 롤백. SseEmitterRegistry 는 인메모리 싱글톤이라 롤백 대상이 아니므로,
// 각 테스트가 랜덤 userId 로 등록하고 finally 에서 자기 emitter 를 정리한다(NotificationSseIntegrationTest 와 동일 규약).
@Transactional
class TournamentItemParsedSseIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var broadcaster: TournamentItemParsedSseBroadcaster

    @Autowired private lateinit var registry: SseEmitterRegistry

    @Autowired private lateinit var tournamentItemRepository: TournamentItemRepository

    @Autowired private lateinit var tournamentUserRepository: TournamentUserRepository

    @Autowired private lateinit var itemSnapshotRepository: ItemSnapshotRepository

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Test
    fun `파싱 완료 시 그 토너먼트 참여자 전원이 출전 좌표와 status=READY 를 받고 비참여자는 못 받는다`() {
        val itemId = 5001L
        val tournamentId = 7001L
        val adder = UUID.randomUUID()
        val participant = UUID.randomUUID()
        val outsider = UUID.randomUUID()
        listOf(adder, participant).forEach { tournamentUserRepository.save(TournamentUser(tournamentId, it)) }
        val snapshotId = snapshotIdFor(itemId)
        val tournamentItemId =
            tournamentItemRepository.saveAll(listOf(TournamentItem(tournamentId, adder, snapshotId))).first().getId()

        val adderEmitter = ItemParsedRecordingEmitter().also { registry.register(adder, it) }
        val participantEmitter = ItemParsedRecordingEmitter().also { registry.register(participant, it) }
        val outsiderEmitter = ItemParsedRecordingEmitter().also { registry.register(outsider, it) }

        try {
            broadcaster.broadcast(snapshotId, ItemStatus.READY)

            // 참여자 전원(올린 본인 adder 포함)이 그 토너먼트 좌표 + READY 를 받는다.
            listOf(adderEmitter, participantEmitter).forEach { emitter ->
                val payload = emitter.payloads().single()
                assertEquals(tournamentId, payload.tournamentId)
                assertEquals(tournamentItemId, payload.tournamentItemId)
                assertEquals(ItemStatus.READY, payload.status)
                assertTrue(emitter.sentData.any { it is String && it.contains("event:silent-sync") })
                // wire 직렬화 contract — type 판별자가 실제 JSON 에 실리는지(클라가 type 으로 분기하므로 누락되면 무라우팅).
                val node = objectMapper.readTree(objectMapper.writeValueAsString(payload))
                assertEquals("TOURNAMENT_ITEM_PARSED", node.get("type").asString())
                assertEquals(ItemStatus.READY.name, node.get("status").asString())
            }
            // 그 토너먼트 비참여자에겐 가지 않는다.
            assertTrue(outsiderEmitter.payloads().isEmpty())
        } finally {
            listOf(adder to adderEmitter, participant to participantEmitter, outsider to outsiderEmitter)
                .forEach { (userId, emitter) -> registry.unregister(userId, emitter) }
        }
    }

    @Test
    fun `파싱 실패 시 참여자는 status=FAILED 로 받는다`() {
        val itemId = 5002L
        val tournamentId = 7002L
        val participant = UUID.randomUUID()
        tournamentUserRepository.save(TournamentUser(tournamentId, participant))
        val snapshotId = snapshotIdFor(itemId)
        tournamentItemRepository.saveAll(listOf(TournamentItem(tournamentId, participant, snapshotId)))
        val emitter = ItemParsedRecordingEmitter().also { registry.register(participant, it) }

        try {
            broadcaster.broadcast(snapshotId, ItemStatus.FAILED)

            assertEquals(ItemStatus.FAILED, emitter.payloads().single().status)
        } finally {
            registry.unregister(participant, emitter)
        }
    }

    @Test
    fun `위시로만 담긴(어느 토너먼트에도 없는) 아이템이면 아무 emitter 에도 보내지 않는다`() {
        // 이 버전을 pin 한 출전(tournament_item)이 없으면 findRoutingBySnapshotId 가 비어 broadcast 가 early return 한다.
        val itemId = 5003L
        val snapshotId = snapshotIdFor(itemId) // snapshot 만 있고 토너먼트엔 안 올림(위시 전용 상황 시뮬레이션)
        val someUser = UUID.randomUUID()
        val emitter = ItemParsedRecordingEmitter().also { registry.register(someUser, it) }

        try {
            broadcaster.broadcast(snapshotId, ItemStatus.READY)

            assertTrue(emitter.payloads().isEmpty())
        } finally {
            registry.unregister(someUser, emitter)
        }
    }

    @Test
    fun `한 아이템이 여러 토너먼트에 출전 중이면 각 토너먼트 참여자가 각자 그 토너먼트 좌표로 받는다`() {
        val itemId = 5004L
        val snapshotId = snapshotIdFor(itemId)
        val tournamentA = 7004L
        val tournamentB = 7005L
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        tournamentUserRepository.save(TournamentUser(tournamentA, userA))
        tournamentUserRepository.save(TournamentUser(tournamentB, userB))
        // 같은 버전(snapshot)을 두 토너먼트가 pin 한다(공유 #825 의 세계) → 같은 사실이므로 두 좌표 모두 받는 것이 맞다.
        val itemIdA = tournamentItemRepository.saveAll(listOf(TournamentItem(tournamentA, userA, snapshotId))).first().getId()
        val itemIdB = tournamentItemRepository.saveAll(listOf(TournamentItem(tournamentB, userB, snapshotId))).first().getId()
        val emitterA = ItemParsedRecordingEmitter().also { registry.register(userA, it) }
        val emitterB = ItemParsedRecordingEmitter().also { registry.register(userB, it) }

        try {
            broadcaster.broadcast(snapshotId, ItemStatus.READY)

            val payloadA = emitterA.payloads().single()
            assertEquals(tournamentA, payloadA.tournamentId)
            assertEquals(itemIdA, payloadA.tournamentItemId)

            val payloadB = emitterB.payloads().single()
            assertEquals(tournamentB, payloadB.tournamentId)
            assertEquals(itemIdB, payloadB.tournamentItemId)
        } finally {
            registry.unregister(userA, emitterA)
            registry.unregister(userB, emitterB)
        }
    }

    @Test
    fun `같은 item 의 다른 버전을 pin 한 카드는 받지 않는다 - 버전 라우팅의 spurious 차단`() {
        // #576 의 핵심 계약: 파싱 사실은 버전의 것이다. 같은 item 이라도 다른 버전(갱신 전 버전 등)을 pin 한
        // 카드에 남의 완료가 전파되면 "아직 안 끝난 카드가 완료로 갱신"되는 오염이 된다.
        val itemId = 5005L
        val versionOne = snapshotIdFor(itemId)
        val versionTwo = snapshotIdFor(itemId)
        val tournamentA = 7006L
        val tournamentB = 7007L
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        tournamentUserRepository.save(TournamentUser(tournamentA, userA))
        tournamentUserRepository.save(TournamentUser(tournamentB, userB))
        tournamentItemRepository.saveAll(listOf(TournamentItem(tournamentA, userA, versionOne)))
        val cardB = tournamentItemRepository.saveAll(listOf(TournamentItem(tournamentB, userB, versionTwo))).first().getId()
        val emitterA = ItemParsedRecordingEmitter().also { registry.register(userA, it) }
        val emitterB = ItemParsedRecordingEmitter().also { registry.register(userB, it) }

        try {
            broadcaster.broadcast(versionTwo, ItemStatus.READY)

            // versionTwo 를 pin 한 카드만 받는다.
            assertEquals(cardB, emitterB.payloads().single().tournamentItemId)
            assertTrue(emitterA.payloads().isEmpty())
        } finally {
            registry.unregister(userA, emitterA)
            registry.unregister(userB, emitterB)
        }
    }

    // 파싱 중(PROCESSING) 버전을 하나 시딩한다 — pin(snapshotId)과 브로드캐스트 키가 같은 버전을 가리켜야 라우팅이 맞아떨어진다.
    private fun snapshotIdFor(itemId: Long): Long = itemSnapshotRepository.save(ItemSnapshot.pending(itemId).apply { markProcessing() }).getId()
}

// send(SseEventBuilder) 를 가로채 실제 IO 없이 전송 내용을 기록한다. build() 가 내놓는 data 항목
// (메타 라인 문자열 + payload 객체)을 그대로 모아, 테스트가 payload 와 이벤트 name 을 단언할 수 있게 한다.
private class ItemParsedRecordingEmitter : SseEmitter() {
    val sentData = CopyOnWriteArrayList<Any>()

    override fun send(builder: SseEmitter.SseEventBuilder) {
        builder.build().forEach { sentData.add(it.data) }
    }

    fun payloads(): List<TournamentItemParsed> = sentData.filterIsInstance<TournamentItemParsed>()
}
