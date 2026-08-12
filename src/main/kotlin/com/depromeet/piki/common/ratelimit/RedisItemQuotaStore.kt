package com.depromeet.piki.common.ratelimit

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import kotlin.math.max

// 고정 윈도우 카운터를 Redis 에 두는 한도 저장소(#339·#927).
//
// Bucket4j 같은 라이브러리 대신 Lua 를 직접 쓰는 이유: 필요한 것이 "창당 N 개" 라는 단순 카운터뿐이고,
// Bucket4j 는 버킷 상태를 **객체로 직렬화해** Redis 에 저장한다. 그러면 무중단 배포 중 구·신버전이 같은 키를
// 공유하는 동안의 호환성을 테스트로 고정해야 한다(테스트 규약의 직렬화/호환성 분류). 문자열 카운터만 쓰면
// 그 부담이 통째로 사라지고, 이 repo 의 기존 Redis 사용(StringRedisTemplate + Lua, RedisRefreshTokenStore)과도 같은 결이다.
@Component
class RedisItemQuotaStore(
    private val redisTemplate: StringRedisTemplate,
) {
    // 잔액 방식 — **요청 크기는 판정에 쓰지 않는다.** 창에 남은 몫이 있으면(누적 < 한도) 요청 크기와 무관하게
    // 통과시키고 쓴 만큼 그대로 더한다. 그래서 누적이 한도를 넘어 "빚"(잔액 음수)이 될 수 있고, 다음 요청부터 거부된다.
    //
    // 요청 크기를 판정에 넣던 방식(누적 + 요청량 > 한도면 전량 거부)을 버린 이유:
    //   - 남은 몫 2 에 이미지 5장을 요청하면 거부되는데, 사용자는 자기 잔액을 모르니 왜 막혔는지 알 수 없고
    //     몇 장으로 줄여야 통과하는지도 안내할 방법이 없다. 잔액 방식은 **마지막 한 번이 항상 성공**하고,
    //     막히는 것은 그 다음부터라 "이번 창의 몫을 다 썼다" 는 경계가 사용자에게 명확해진다.
    //   - 부분 성공 계약을 고민할 필요가 사라진다(요청은 통째로 통과하거나 통째로 거부된다).
    //
    // 초과 노출은 유한하다: 창당 최대 소비는 (한도 + 1회 최대 요청량)으로 바운드된다. 잔액 1 에서 5장이 들어와도
    // -4 가 최악이고, 그 뒤로는 전부 거부되기 때문이다. 무한 초과가 아니라 계산 가능한 상한이라 받아들일 수 있다.
    // (파싱 후 실제 소비를 정산하는 후속 과제 #910 이 붙으면 그 정산분도 같은 방식으로 음수에 얹힌다.)
    //
    // 두 축(요청자 몫 · 전역 가용량)을 **한 스크립트로 함께** 판정·차감한다. 나눠 부르면 "요청자 몫은 깎였는데
    // 전역이 차서 거부" 인 요청이 생기는데, 그 사용자는 503 을 받고 안내대로 재시도할 때마다 자기 몫을 잃는다.
    // 결국 전역이 풀린 뒤에도 자기 한도에 걸려 429 를 받는다 — 재시도 안내가 사용자를 자해하게 만드는 셈이다.
    fun tryConsume(
        ownerKey: String,
        capacityKey: String,
        amount: Int,
        ownerLimit: Int,
        capacityLimit: Int,
        windowMillis: Long,
    ): ItemQuotaVerdict {
        require(amount > 0) { "차감량($amount)은 양수여야 한다 — 0 건 등록은 애초에 이 경로에 오지 않는다." }
        val result =
            redisTemplate.execute(
                CONSUME_SCRIPT,
                listOf(ownerKey, capacityKey),
                amount.toString(),
                ownerLimit.toString(),
                capacityLimit.toString(),
                windowMillis.toString(),
            ) ?: error("아이템 한도 Lua script 가 null 을 반환했다 (ownerKey=$ownerKey)")

        return when {
            result.startsWith(ALLOWED_PREFIX) ->
                ItemQuotaVerdict.Allowed(result.removePrefix(ALLOWED_PREFIX).toLong())
            result.startsWith(OWNER_EXCEEDED_PREFIX) ->
                ItemQuotaVerdict.OwnerExceeded(retryAfterSecondsOf(result.removePrefix(OWNER_EXCEEDED_PREFIX)))
            result.startsWith(CAPACITY_EXCEEDED_PREFIX) ->
                ItemQuotaVerdict.CapacityExceeded(retryAfterSecondsOf(result.removePrefix(CAPACITY_EXCEEDED_PREFIX)))
            else -> error("아이템 한도 Lua script 가 예상 못한 값을 반환했다: $result (ownerKey=$ownerKey)")
        }
    }

    // 올림 + 최소 1초 — 남은 시간이 0.2초여도 Retry-After: 0 을 주면 클라가 즉시 재시도해 또 거부된다.
    private fun retryAfterSecondsOf(remainingMillis: String): Long =
        max(1L, (remainingMillis.toLong() + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND)

    companion object {
        // 전역 가용량 카운터 키(#927). 요청자 몫과 달리 서비스에 하나뿐이라 접두사 + 식별자가 아니라 고정 키다.
        const val CAPACITY_KEY = "quota:item:capacity"

        private const val ALLOWED_PREFIX = "A:"
        private const val OWNER_EXCEEDED_PREFIX = "O:"
        private const val CAPACITY_EXCEEDED_PREFIX = "C:"
        private const val MILLIS_PER_SECOND = 1_000L

        // 판정과 차감을 한 스크립트로 원자화한다. GET → 비교 → INCRBY 를 앱에서 나눠 하면 동시 요청이 각자
        // 통과 판정을 받아 잔액을 예상보다 깊게 파고들 수 있다(check-then-act race). Redis 싱글스레드 직렬화가 그걸 막는다.
        //
        // KEYS[1]=요청자 몫 키, KEYS[2]=전역 가용량 키
        // ARGV[1]=차감량, ARGV[2]=요청자 한도, ARGV[3]=전역 상한, ARGV[4]=창 길이(ms)
        //
        // 판정은 각 축마다 `current >= limit` 하나다 — **요청량(amount)은 판정에 쓰지 않고 차감에만 쓴다.** 남은 몫이
        // 있으면 크기와 무관하게 들여보내고, 넘긴 만큼은 다음 요청이 갚는다(위 잔액 방식 주석).
        //
        // **요청자 몫을 먼저 본다.** 둘 다 소진된 사용자에게 503("지금 요청이 많아요")을 주면 실제 원인은 자기가
        // 다 쓴 것인데 서버 탓으로 읽힌다. 자기 몫이 남아 있을 때만 전역을 따진다.
        //
        // 반환:
        //   "A:<전역 누적>"  허용 — 두 축 모두 차감 후 전역 카운터 값(한도를 넘겼을 수 있다)
        //   "O:<남은 ms>"    요청자 몫 소진 → 429. 어느 카운터도 차감하지 않음
        //   "C:<남은 ms>"    전역 가용량 소진 → 503. 어느 카운터도 차감하지 않음
        //
        // 거부 시 INCRBY 를 하지 않는 것이 중요하다. 거부분까지 누적하면 한도에 걸린 사용자가 재시도할 때마다
        // 카운터가 계속 올라, TTL 로 창이 끝나도 이미 한도를 넘긴 상태로 시작하는 일이 생긴다(사실상 영구 차단).
        //
        // TTL 은 INCRBY 직후 PTTL 이 음수(-1: TTL 없음)일 때만 건다. "누적값 == 차감량이면 첫 차감" 으로 판정하면
        // 창 도중 키가 TTL 없이 남는 경로가 생겼을 때 그 키가 영구화된다 — PTTL 로 보면 그 경우까지 복구된다.
        //
        // 키 둘을 한 스크립트에서 만지므로 Redis Cluster 로 가면 같은 해시 슬롯이어야 한다. 현재는 단일 인스턴스라
        // 제약이 없지만, 클러스터 전환 시 이 스크립트가 CROSSSLOT 으로 깨진다는 점을 여기 남겨둔다.
        private val CONSUME_SCRIPT =
            DefaultRedisScript<String>().apply {
                setScriptText(
                    """
                    local amount = tonumber(ARGV[1])
                    local ownerLimit = tonumber(ARGV[2])
                    local capacityLimit = tonumber(ARGV[3])
                    local windowMillis = ARGV[4]

                    local owner = tonumber(redis.call('GET', KEYS[1]) or '0')
                    if owner >= ownerLimit then
                        local ttl = redis.call('PTTL', KEYS[1])
                        if ttl < 0 then ttl = 0 end
                        return 'O:' .. ttl
                    end

                    local capacity = tonumber(redis.call('GET', KEYS[2]) or '0')
                    if capacity >= capacityLimit then
                        local ttl = redis.call('PTTL', KEYS[2])
                        if ttl < 0 then ttl = 0 end
                        return 'C:' .. ttl
                    end

                    redis.call('INCRBY', KEYS[1], amount)
                    if redis.call('PTTL', KEYS[1]) < 0 then
                        redis.call('PEXPIRE', KEYS[1], windowMillis)
                    end
                    local capacityUsed = redis.call('INCRBY', KEYS[2], amount)
                    if redis.call('PTTL', KEYS[2]) < 0 then
                        redis.call('PEXPIRE', KEYS[2], windowMillis)
                    end
                    return 'A:' .. capacityUsed
                    """.trimIndent(),
                )
                setResultType(String::class.java)
            }
    }
}
