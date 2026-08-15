package com.depromeet.piki.common.ratelimit

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.TimeUnit

// 지금 창의 사용량을 읽는다(#934 백오피스 현황). 판정 경로(RedisItemQuotaStore)와 나눠 둔 이유는 책임이 달라서다 —
// 저쪽은 판정과 차감을 원자로 묶는 쓰기 경로이고, 여기는 부작용 없는 읽기다.
//
// 상위 사용자 목록(랭킹)은 두지 않는다. Redis 는 "조건에 맞는 키 전부" 를 값싸게 못 주므로 SCAN 으로 활성 사용자
// 키를 전수 훑어야 하는데, 같은 Redis 를 refresh 토큰 저장소가 함께 쓰고 있어 화면 한 번이 로그인 지연으로 번진다.
// 특정 계정 조회(키 하나)로 충분하다 — 누가 많이 쓰는지는 메트릭·로그가 답한다.
@Component
class ItemQuotaUsageReader(
    private val redisTemplate: StringRedisTemplate,
    private val settings: ItemQuotaSettings,
) {
    fun capacity(): ItemQuotaUsage = read(RedisItemQuotaStore.CAPACITY_KEY, settings.current().capacityLimit)

    fun user(userId: UUID): ItemQuotaUsage =
        read(RedisItemQuotaStore.USER_KEY_PREFIX + userId, settings.current().userLimit)

    // 값과 TTL 을 따로 읽으므로 그 사이 창이 만료될 수 있다. 그 경우 used 는 옛 값, resetInSeconds 는 null 이 되는데
    // 운영자가 보는 현황 화면이라 한 틱 어긋나는 것은 문제가 되지 않는다(판정은 Lua 가 원자로 한다).
    private fun read(
        key: String,
        limit: Int,
    ): ItemQuotaUsage {
        val used = redisTemplate.opsForValue().get(key)?.toLongOrNull() ?: 0
        // -2(키 없음)·-1(TTL 없음) 은 둘 다 "남은 창을 말할 수 없음" 이라 null 로 접는다.
        val ttlSeconds = redisTemplate.getExpire(key, TimeUnit.SECONDS)?.takeIf { it > 0 }
        return ItemQuotaUsage(used = used, limit = limit, resetInSeconds = ttlSeconds)
    }
}

// resetInSeconds 가 null 이면 이번 창에 아직 아무도 쓰지 않은 것이다(카운터 키가 없어 TTL 도 없다).
data class ItemQuotaUsage(
    val used: Long,
    val limit: Int,
    val resetInSeconds: Long?,
) {
    // 음수를 0 으로 접지 않는다 — 잔액 방식이라 마지막 한 번이 한도를 넘길 수 있고(예: 잔액 1 에 이미지 5장),
    // 운영자에게는 "얼마나 넘겼나" 가 곧 신호다. 0 으로 보이면 그 초과가 화면에서 사라진다.
    val remaining: Long get() = limit - used

    // 화면 게이지용. 상한을 넘겨도 100 을 넘지 않게 잘라 막대가 넘치지 않게 한다.
    val usedPercent: Int get() = ((used * 100) / limit).coerceIn(0, 100).toInt()
}
