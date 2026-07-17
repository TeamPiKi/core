package com.depromeet.piki.notification.sse

import org.slf4j.LoggerFactory
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.Limit
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

// SseEventLog 의 Redis Stream 구현 — 유저별 스트림(sse:events:{userId})에 XADD 로 적재하고,
// 이벤트 id 는 Redis 가 발급하는 stream entry id(예: "1752741234567-0")를 그대로 쓴다.
//
// 저장소를 인메모리가 아니라 Redis 에 두는 이유:
// - 배포(blue-green 전환)가 곧 전 연결 동시 재연결 시점인데, 인메모리 버퍼는 그 순간 비어 있어 무력하다.
//   Redis 는 앱 배포와 수명이 분리돼 재연결 직후에도 로그가 살아 있다.
// - 스케일아웃 시 유저별 단조 id 채번은 인스턴스 로컬로 불가능하다. XADD 의 id 발급이 이를 중앙에서 해결한다.
//
// 한계(선택된 트레이드오프): 적재는 MySQL 트랜잭션과 원자적이지 않다. emit 경로에 도달하지 못한
// 이벤트(executor 태스크 거부 등)는 로그에도 남지 않으며, 그 틈은 클라이언트 재조회 fallback 이 커버한다.
@Component
class RedisSseEventLog(
    private val redisTemplate: StringRedisTemplate,
) : SseEventLog {
    private val log = LoggerFactory.getLogger(javaClass)

    // 적재 + 상한 trim + TTL 갱신. 어느 단계든 실패하면 null 로 degrade(live 전송만) — SSE 는 best-effort
    // 채널이라 Redis 장애가 알림 발송 자체를 막으면 안 된다.
    override fun append(
        userId: UUID,
        eventName: String,
        payloadJson: String,
    ): String? =
        runCatching {
            val key = key(userId)
            val ops = redisTemplate.opsForStream<String, String>()
            val recordId =
                ops.add(MapRecord.create(key, mapOf(FIELD_NAME to eventName, FIELD_PAYLOAD to payloadJson)))
                    ?: error("XADD 가 record id 를 돌려주지 않았다")
            // 정확 trim(비근사): "trim 이 일어났다면 잔존 건수가 MAX_LEN" 이 정확히 성립해야
            // replay 의 상한 초과 판정(trim 구멍 배제 증명)이 근사 오차 없이 선다. 스트림이 짧아 비용은 무시 가능.
            ops.trim(key, MAX_LEN)
            redisTemplate.expire(key, TTL)
            recordId.value
        }.getOrElse { e ->
            log.warn("SSE 이벤트 로그 적재 실패 - live 전송만 진행 userId={} event={}", userId, eventName, e)
            null
        }

    override fun readAfter(
        userId: UUID,
        lastEventId: String,
        limit: Int,
    ): List<SseEventRecord> =
        runCatching {
            redisTemplate
                .opsForStream<String, String>()
                .range(
                    key(userId),
                    Range.rightUnbounded(Range.Bound.exclusive(lastEventId)),
                    Limit.limit().count(limit),
                ).orEmpty()
                .mapNotNull { record -> toEventRecord(record.id.value, record.value) }
        }.getOrElse { e ->
            log.warn("SSE 이벤트 로그 조회 실패 - replay 생략 userId={}", userId, e)
            emptyList()
        }

    private fun key(userId: UUID): String = "$KEY_PREFIX$userId"

    companion object {
        private const val KEY_PREFIX = "sse:events:"

        // 스트림당 보관 상한. SseReconnectReplayer.REPLAY_LIMIT 보다 반드시 커야 한다 —
        // trim 으로 lastEventId 이후 항목이 잘렸다면(중간 구멍) 잔존 항목 전부가 lastEventId 초과분이 되어
        // 조회 건수가 MAX_LEN(> REPLAY_LIMIT)에 도달하므로, replay 가 "상한 초과 통째 생략" 으로 귀결된다.
        // 이 관계가 "받은 만큼은 연속" 계약을 trim 상황에서도 지킨다. (관계는 테스트가 단언한다.)
        const val MAX_LEN = 200L

        // 스트림 보존 시간 — 적재 때마다 갱신된다. 이보다 긴 공백(장기 미접속) 뒤의 재연결은 replay 완전성을
        // 보장하지 않으며(만료로 소실), 클라이언트 재조회 fallback 이 복구를 책임진다. 실제 재연결 주기는
        // 타임아웃 기준 최대 30분이라 충분한 여유다.
        val TTL: Duration = Duration.ofHours(24)

        // 저장 필드 키 — blue-green 전환 중 구·신버전 인스턴스가 같은 스트림을 읽는 계약이다.
        // 키를 바꾸면 배포 중 상대 버전이 적재한 항목을 못 읽으므로, 변경 시 호환성 테스트가 깨진다.
        const val FIELD_NAME = "name"
        const val FIELD_PAYLOAD = "payload"

        // 항목 -> 레코드 변환. 계약 밖 항목(필드 누락)은 replay 대상에서 제외한다(null).
        // 순수 함수로 분리해 저장 스키마 호환성 테스트가 Redis 없이 fixture 로 검증한다.
        internal fun toEventRecord(
            id: String,
            fields: Map<String, String>,
        ): SseEventRecord? {
            val name = fields[FIELD_NAME] ?: return null
            val payload = fields[FIELD_PAYLOAD] ?: return null
            return SseEventRecord(id, name, payload)
        }
    }
}
