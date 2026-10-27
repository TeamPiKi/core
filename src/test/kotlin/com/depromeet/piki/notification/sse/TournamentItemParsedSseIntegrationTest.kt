package com.depromeet.piki.notification.sse

import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.event.ItemSlotMerged
import com.depromeet.piki.notification.controller.dto.TournamentItemMerged
import com.depromeet.piki.notification.controller.dto.TournamentItemParsed
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.tournament.domain.TournamentItem
import com.depromeet.piki.tournament.domain.TournamentUser
import com.depromeet.piki.tournament.repository.TournamentItemRepository
import com.depromeet.piki.tournament.repository.TournamentUserRepository
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.repository.WishRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 수신자·좌표를 요청한 출전 행과 참여자 행에서 도출하므로 통합으로 검증한다.
// SseEmitterRegistry 는 인메모리 싱글톤이라 롤백되지 않아 각 테스트가 finally 에서 자기 emitter 를 정리한다
@Transactional
class TournamentItemParsedSseIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var broadcaster: TournamentItemParsedSseBroadcaster

    @Autowired private lateinit var registry: SseEmitterRegistry

    @Autowired private lateinit var tournamentItemRepository: TournamentItemRepository

    @Autowired private lateinit var tournamentUserRepository: TournamentUserRepository

    @Autowired private lateinit var wishRepository: WishRepository

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Test
    fun `파싱 완료 시 그 토너먼트 참여자 전원이 출전 좌표와 status=READY 를 받고 비참여자는 못 받는다`() {
        val tournamentId = 7001L
        val adder = UUID.randomUUID()
        val participant = UUID.randomUUID()
        val outsider = UUID.randomUUID()
        listOf(adder, participant).forEach { tournamentUserRepository.save(TournamentUser(tournamentId, it)) }
        val tournamentItemId = tournamentItemRepository.save(TournamentItem(tournamentId, adder, itemId = null)).getId()

        val adderEmitter = ItemParsedRecordingEmitter().also { registry.register(SseConnection(adder, it)) }
        val participantEmitter = ItemParsedRecordingEmitter().also { registry.register(SseConnection(participant, it)) }
        val outsiderEmitter = ItemParsedRecordingEmitter().also { registry.register(SseConnection(outsider, it)) }

        try {
            broadcaster.broadcast(ItemSlot.tournamentItem(tournamentItemId), ItemStatus.READY)

            listOf(adderEmitter, participantEmitter).forEach { emitter ->
                val payload = emitter.payloads().single()
                assertEquals(tournamentId, payload.tournamentId)
                assertEquals(tournamentItemId, payload.tournamentItemId)
                assertEquals(ItemStatus.READY, payload.status)
                assertTrue(emitter.sentData.any { it is String && it.contains("event:silent-sync") })
                // 클라가 type 으로 분기하므로 판별자가 실제 JSON 에 실리는지 본다
                val node = objectMapper.readTree(objectMapper.writeValueAsString(payload))
                assertEquals("TOURNAMENT_ITEM_PARSED", node.get("type").asString())
                assertEquals(ItemStatus.READY.name, node.get("status").asString())
            }
            assertTrue(outsiderEmitter.payloads().isEmpty())
        } finally {
            listOf(adder, participant, outsider).forEach { registry.removeAll(it) }
        }
    }

    @Test
    fun `파싱 실패 시 참여자는 status=FAILED 로 받는다`() {
        val tournamentId = 7002L
        val participant = UUID.randomUUID()
        tournamentUserRepository.save(TournamentUser(tournamentId, participant))
        val tournamentItemId = tournamentItemRepository.save(TournamentItem(tournamentId, participant, itemId = null)).getId()
        val emitter = ItemParsedRecordingEmitter().also { registry.register(SseConnection(participant, it)) }

        try {
            broadcaster.broadcast(ItemSlot.tournamentItem(tournamentItemId), ItemStatus.FAILED)

            assertEquals(ItemStatus.FAILED, emitter.payloads().single().status)
        } finally {
            registry.removeAll(participant)
        }
    }

    @Test
    fun `후보가 같은 상품의 다른 후보에 합쳐지면 참여자 전원이 사라진 후보와 남은 후보 좌표를 받는다`() {
        val tournamentId = 7006L
        val adder = UUID.randomUUID()
        val participant = UUID.randomUUID()
        listOf(adder, participant).forEach { tournamentUserRepository.save(TournamentUser(tournamentId, it)) }
        val holderId = tournamentItemRepository.save(TournamentItem(tournamentId, participant, itemId = null)).getId()
        val mergedId = tournamentItemRepository.save(TournamentItem(tournamentId, adder, itemId = null)).getId()
        val emitters = listOf(adder, participant).map { user -> ItemParsedRecordingEmitter().also { registry.register(SseConnection(user, it)) } }

        try {
            broadcaster.broadcastMerged(ItemSlotMerged(ItemSlot.tournamentItem(mergedId), ItemSlot.tournamentItem(holderId), adder, holderOwner = participant, itemId = 5006L))

            emitters.forEach { emitter ->
                val payload = emitter.sentData.filterIsInstance<TournamentItemMerged>().single()
                assertEquals(TournamentItemMerged(tournamentId, tournamentItemId = mergedId, mergedIntoTournamentItemId = holderId), payload)
                assertEquals("TOURNAMENT_ITEM_MERGED", objectMapper.readTree(objectMapper.writeValueAsString(payload)).get("type").asString())
            }
        } finally {
            listOf(adder, participant).forEach { registry.removeAll(it) }
        }
    }

    @Test
    fun `위시가 요청한 파싱이면 같은 아이템이 토너먼트에 출전 중이어도 아무 emitter 에도 보내지 않는다`() {
        val itemId = 5003L
        val tournamentId = 7003L
        val owner = UUID.randomUUID()
        val participant = UUID.randomUUID()
        tournamentUserRepository.save(TournamentUser(tournamentId, participant))
        tournamentItemRepository.save(TournamentItem(tournamentId, participant, itemId))
        val wishSlot = wishRepository.save(Wish(owner, itemId)).slot
        val ownerEmitter = ItemParsedRecordingEmitter().also { registry.register(SseConnection(owner, it)) }
        val participantEmitter = ItemParsedRecordingEmitter().also { registry.register(SseConnection(participant, it)) }

        try {
            broadcaster.broadcast(wishSlot, ItemStatus.READY)

            assertTrue(ownerEmitter.payloads().isEmpty())
            assertTrue(participantEmitter.payloads().isEmpty())
        } finally {
            listOf(owner, participant).forEach { registry.removeAll(it) }
        }
    }

    @Test
    fun `같은 아이템이 다른 토너먼트에도 출전 중이면 요청한 출전의 토너먼트 참여자만 받는다`() {
        val itemId = 5004L
        val tournamentA = 7004L
        val tournamentB = 7005L
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        tournamentUserRepository.save(TournamentUser(tournamentA, userA))
        tournamentUserRepository.save(TournamentUser(tournamentB, userB))
        val cardA = tournamentItemRepository.save(TournamentItem(tournamentA, userA, itemId)).getId()
        tournamentItemRepository.save(TournamentItem(tournamentB, userB, itemId))
        val emitterA = ItemParsedRecordingEmitter().also { registry.register(SseConnection(userA, it)) }
        val emitterB = ItemParsedRecordingEmitter().also { registry.register(SseConnection(userB, it)) }

        try {
            broadcaster.broadcast(ItemSlot.tournamentItem(cardA), ItemStatus.READY)

            val payloadA = emitterA.payloads().single()
            assertEquals(tournamentA, payloadA.tournamentId)
            assertEquals(cardA, payloadA.tournamentItemId)
            assertTrue(emitterB.payloads().isEmpty())
        } finally {
            listOf(userA, userB).forEach { registry.removeAll(it) }
        }
    }
}

// send(SseEventBuilder) 를 가로채 실제 IO 없이 build() 의 data 항목(메타 라인 문자열과 payload 객체)을 기록한다
private class ItemParsedRecordingEmitter : SseEmitter() {
    val sentData = CopyOnWriteArrayList<Any>()

    override fun send(builder: SseEmitter.SseEventBuilder) {
        builder.build().forEach { sentData.add(it.data) }
    }

    fun payloads(): List<TournamentItemParsed> = sentData.filterIsInstance<TournamentItemParsed>()
}
