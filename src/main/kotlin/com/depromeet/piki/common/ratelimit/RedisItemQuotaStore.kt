package com.depromeet.piki.common.ratelimit

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import kotlin.math.max

// 고정 윈도우 카운터를 Redis 에 두는 한도 저장소(#339).
//
// Bucket4j 같은 라이브러리 대신 Lua 를 직접 쓰는 이유: 필요한 것이 "창당 N 개" 라는 단순 카운터뿐이고,
// Bucket4j 는 버킷 상태를 **객체로 직렬화해** Redis 에 저장한다. 그러면 무중단 배포 중 구·신버전이 같은 키를
// 공유하는 동안의 호환성을 테스트로 고정해야 한다(테스트 규약의 직렬화/호환성 분류). 문자열 카운터만 쓰면
// 그 부담이 통째로 사라지고, 이 repo 의 기존 Redis 사용(StringRedisTemplate + Lua, RedisRefreshTokenStore)과도 같은 결이다.
@Component
class RedisItemQuotaStore(
    private val redisTemplate: StringRedisTemplate,
) {
    // all-or-nothing 차감. 이미지 5장 요청이 한도 3 만 남은 상태에서 3장만 통과하고 2장이 잘리면 클라이언트가
    // "일부만 등록됨" 을 다뤄야 하는데, 등록 API 는 그런 부분 성공 계약이 없다. 그래서 전부 되거나 전부 거부다.
    fun tryConsume(
        key: String,
        amount: Int,
        limit: Int,
        windowMillis: Long,
    ): ItemQuotaVerdict {
        require(amount > 0) { "차감량($amount)은 양수여야 한다 — 0 건 등록은 애초에 이 경로에 오지 않는다." }
        val result =
            redisTemplate.execute(
                CONSUME_SCRIPT,
                listOf(key),
                amount.toString(),
                limit.toString(),
                windowMillis.toString(),
            ) ?: error("아이템 한도 Lua script 가 null 을 반환했다 (key=$key)")

        return when {
            result.startsWith(ALLOWED_PREFIX) -> ItemQuotaVerdict.Allowed
            result.startsWith(EXCEEDED_PREFIX) -> {
                val remainingMillis = result.removePrefix(EXCEEDED_PREFIX).toLong()
                // 올림 + 최소 1초 — 남은 시간이 0.2초여도 Retry-After: 0 을 주면 클라가 즉시 재시도해 또 거부된다.
                ItemQuotaVerdict.Exceeded(max(1L, (remainingMillis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND))
            }
            else -> error("아이템 한도 Lua script 가 예상 못한 값을 반환했다: $result (key=$key)")
        }
    }

    companion object {
        private const val ALLOWED_PREFIX = "A:"
        private const val EXCEEDED_PREFIX = "X:"
        private const val MILLIS_PER_SECOND = 1_000L

        // 판정과 차감을 한 스크립트로 원자화한다. GET → 비교 → INCRBY 를 앱에서 나눠 하면 동시 요청이 각자
        // 통과 판정을 받아 한도를 넘겨 차감할 수 있다(check-then-act race). Redis 싱글스레드 직렬화가 그걸 막는다.
        //
        // KEYS[1]=카운터 키, ARGV[1]=차감량, ARGV[2]=한도, ARGV[3]=창 길이(ms)
        //
        // 반환:
        //   "A:<누적>"     허용 — 차감 후 누적값
        //   "X:<남은 ms>"  거부 — 차감하지 않음. 창이 리셋되기까지 남은 시간
        //
        // 거부 시 INCRBY 를 하지 않는 것이 중요하다. 거부분까지 누적하면 한도에 걸린 사용자가 재시도할 때마다
        // 카운터가 계속 올라, TTL 로 창이 끝나도 이미 한도를 넘긴 상태로 시작하는 일이 생긴다(사실상 영구 차단).
        //
        // TTL 은 INCRBY 직후 PTTL 이 음수(-1: TTL 없음)일 때만 건다. "누적값 == 차감량이면 첫 차감" 으로 판정하면
        // 창 도중 키가 TTL 없이 남는 경로가 생겼을 때 그 키가 영구화된다 — PTTL 로 보면 그 경우까지 복구된다.
        private val CONSUME_SCRIPT =
            DefaultRedisScript<String>().apply {
                setScriptText(
                    """
                    local amount = tonumber(ARGV[1])
                    local limit = tonumber(ARGV[2])
                    local current = tonumber(redis.call('GET', KEYS[1]) or '0')
                    if current + amount > limit then
                        local ttl = redis.call('PTTL', KEYS[1])
                        if ttl < 0 then ttl = 0 end
                        return 'X:' .. ttl
                    end
                    local updated = redis.call('INCRBY', KEYS[1], amount)
                    if redis.call('PTTL', KEYS[1]) < 0 then
                        redis.call('PEXPIRE', KEYS[1], ARGV[3])
                    end
                    return 'A:' .. updated
                    """.trimIndent(),
                )
                setResultType(String::class.java)
            }
    }
}
