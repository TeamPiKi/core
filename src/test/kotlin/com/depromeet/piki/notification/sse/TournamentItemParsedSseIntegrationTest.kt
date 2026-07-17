package com.depromeet.piki.notification.sse

import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.RecordingSseEmitter
import com.depromeet.piki.tournament.domain.TournamentItem
import com.depromeet.piki.tournament.domain.TournamentUser
import com.depromeet.piki.tournament.repository.TournamentItemRepository
import com.depromeet.piki.tournament.repository.TournamentUserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 토너먼트 출전 아이템의 파싱 완료/실패 → 참여자 SSE 조용한 갱신(silent-sync, type=TOURNAMENT_ITEM_PARSED)을 실제 빈으로 검증한다.
// 브로드캐스터의 수신자·좌표 도출은 DB 역조회(tournament_item⋈item_snapshot, tournament_user)에 의존하므로 통합으로 검증한다.
// payload 단언은 운영 경로가 직렬화해 실은 와이어 JSON 을 트리로 파싱해 한다 — 클라가 type 으로 분기하므로
// 판별자 누락이 곧바로 드러난다. 영속 fixture 는 @Transactional 자동 롤백. SseEmitterRegistry 는 인메모리
// 싱글톤이라 롤백 대상이 아니므로, 각 테스트가 랜덤 userId 로 등록하고 finally 에서 자기 emitter 를 정리한다.
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
        val tournamentItemId =
            tournamentItemRepository.saveAll(listOf(TournamentItem(tournamentId, adder, snapshotIdFor(itemId)))).first().getId()

        val adderEmitter = RecordingSseEmitter().also { registry.register(adder, it) }
        val participantEmitter = RecordingSseEmitter().also { registry.register(participant, it) }
        val outsiderEmitter = RecordingSseEmitter().also { registry.register(outsider, it) }

        try {
            broadcaster.broadcast(itemId, ItemStatus.READY)

            // 참여자 전원(올린 본인 adder 포함)이 그 토너먼트 좌표 + READY 를 받는다.
            // 와이어 JSON 단언이라 type 판별자(클라 분기 키) 누락도 함께 잡는다.
            listOf(adderEmitter, participantEmitter).forEach { emitter ->
                val payload = silentSyncPayload(emitter)
                assertEquals("TOURNAMENT_ITEM_PARSED", payload.get("type").asString())
                assertEquals(tournamentId, payload.get("tournamentId").asLong())
                assertEquals(tournamentItemId, payload.get("tournamentItemId").asLong())
                assertEquals(ItemStatus.READY.name, payload.get("status").asString())
            }
            // 그 토너먼트 비참여자에겐 가지 않는다.
            assertTrue(outsiderEmitter.sends.isEmpty())
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
        tournamentItemRepository.saveAll(listOf(TournamentItem(tournamentId, participant, snapshotIdFor(itemId))))
        val emitter = RecordingSseEmitter().also { registry.register(participant, it) }

        try {
            broadcaster.broadcast(itemId, ItemStatus.FAILED)

            assertEquals(ItemStatus.FAILED.name, silentSyncPayload(emitter).get("status").asString())
        } finally {
            registry.unregister(participant, emitter)
        }
    }

    @Test
    fun `위시로만 담긴(어느 토너먼트에도 없는) 아이템이면 아무 emitter 에도 보내지 않는다`() {
        // 토너먼트 출전(tournament_item)이 없으면 findRoutingByItemId 가 비어 broadcast 가 early return 한다.
        val itemId = 5003L
        snapshotIdFor(itemId) // snapshot 만 있고 토너먼트엔 안 올림(위시 전용 상황 시뮬레이션)
        val someUser = UUID.randomUUID()
        val emitter = RecordingSseEmitter().also { registry.register(someUser, it) }

        try {
            broadcaster.broadcast(itemId, ItemStatus.READY)

            assertTrue(emitter.sends.isEmpty())
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
        // 같은 item(=같은 snapshot)을 두 토너먼트에 각각 올린다 → 좌표(tournamentItemId)가 토너먼트별로 다르다.
        val itemIdA = tournamentItemRepository.saveAll(listOf(TournamentItem(tournamentA, userA, snapshotId))).first().getId()
        val itemIdB = tournamentItemRepository.saveAll(listOf(TournamentItem(tournamentB, userB, snapshotId))).first().getId()
        val emitterA = RecordingSseEmitter().also { registry.register(userA, it) }
        val emitterB = RecordingSseEmitter().also { registry.register(userB, it) }

        try {
            broadcaster.broadcast(itemId, ItemStatus.READY)

            val payloadA = silentSyncPayload(emitterA)
            assertEquals(tournamentA, payloadA.get("tournamentId").asLong())
            assertEquals(itemIdA, payloadA.get("tournamentItemId").asLong())

            val payloadB = silentSyncPayload(emitterB)
            assertEquals(tournamentB, payloadB.get("tournamentId").asLong())
            assertEquals(itemIdB, payloadB.get("tournamentItemId").asLong())
        } finally {
            registry.unregister(userA, emitterA)
            registry.unregister(userB, emitterB)
        }
    }

    private fun silentSyncPayload(emitter: RecordingSseEmitter): JsonNode =
        objectMapper.readTree(emitter.payloadsOf("silent-sync").single())

    // 알림 역조회는 tournament_item→item_snapshots 를 snapshot_id 로 조인해 s.item_id 로 매칭한다.
    // 그 itemId 로 시딩한 snapshot 의 id 를 tournament_item 의 snapshotId 로 넘겨야 역조회가 맞아떨어진다.
    private fun snapshotIdFor(itemId: Long): Long = itemSnapshotRepository.save(ItemSnapshot.pending(itemId).apply { markProcessing() }).getId()
}
