package com.depromeet.piki.support

import com.depromeet.piki.notification.sse.SseEventLog
import com.depromeet.piki.notification.sse.SseEventRecord
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

// SseEventLog 의 인메모리 stub — Redis 없이 적재·replay 흐름을 결정적으로 검증한다 (StubRefreshTokenStore 와 같은 결).
// id 는 실제 Redis stream entry id 형식("{ms}-{seq}")을 흉내 낸 "{단조 counter}-0" 이다 — 컨트롤러의
// Last-Event-ID 형식 검증(\d+-\d+)을 그대로 통과한다. 실제 Redis 의 XADD·XRANGE·trim·TTL 거동은
// RedisSseEventLogIntegrationTest 가 실 컨테이너로 책임진다.
class StubSseEventLog : SseEventLog {
    private val streams = ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEventRecord>>()
    private val sequence = AtomicLong(0)

    // default: 정상 적재. 테스트에서 onAppend = { _, _, _ -> null } 로 적재 실패(저장소 장애) 시뮬레이션.
    // 덮어쓴 테스트는 finally 에서 reset() 으로 되돌린다 (공유 컨텍스트라 다음 테스트로 누수되면 안 된다).
    var onAppend: (UUID, String, String) -> String? = ::defaultAppend

    override fun append(
        userId: UUID,
        eventName: String,
        payloadJson: String,
    ): String? = onAppend(userId, eventName, payloadJson)

    override fun readAfter(
        userId: UUID,
        lastEventId: String,
        limit: Int,
    ): List<SseEventRecord> =
        streams[userId]
            .orEmpty()
            .filter { compareIds(it.id, lastEventId) > 0 }
            .take(limit)

    // 테스트가 replay 기준점(id)을 잡기 위해 그 유저의 적재분 전체를 본다.
    fun entriesOf(userId: UUID): List<SseEventRecord> = streams[userId].orEmpty()

    // 상한 초과 등 volume 시나리오용 직접 적재 (운영 경로 우회).
    fun seed(
        userId: UUID,
        eventName: String,
        payloadJson: String,
    ): String = requireNotNull(defaultAppend(userId, eventName, payloadJson))

    fun reset() {
        streams.clear()
        onAppend = ::defaultAppend
    }

    private fun defaultAppend(
        userId: UUID,
        eventName: String,
        payloadJson: String,
    ): String {
        val id = "${sequence.incrementAndGet()}-0"
        streams.computeIfAbsent(userId) { CopyOnWriteArrayList() }.add(SseEventRecord(id, eventName, payloadJson))
        return id
    }

    // stream entry id("{ms}-{seq}") 의 숫자 비교 — 문자열 비교는 자릿수가 다르면 순서가 깨진다.
    private fun compareIds(
        a: String,
        b: String,
    ): Int {
        val (aMs, aSeq) = a.split("-").map(String::toLong)
        val (bMs, bSeq) = b.split("-").map(String::toLong)
        return compareValuesBy(aMs to aSeq, bMs to bSeq, { it.first }, { it.second })
    }
}
