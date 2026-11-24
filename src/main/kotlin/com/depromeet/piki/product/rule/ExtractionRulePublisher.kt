package com.depromeet.piki.product.rule

import com.depromeet.piki.contracts.extraction.v1.ExtractionProto
import com.depromeet.piki.contracts.extraction.v1.ExtractionRuleSet
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

// 켜진 규칙 전체를 extractor 가 읽는 Redis 키에 올린다. 키 이름의 정본은 TeamPiKi/infra 의
// contracts/extraction.proto(ExtractionRuleSet 의 redis_key 옵션)다.
//
// 읽기·비교·쓰기가 원자적이지 않아 두 인스턴스가 겹치면 옛 판이 새 판을 잠깐 덮을 수 있다. 값이 DB 만으로
// 정해지므로 다음 대조에서 같은 문자열로 모인다.
@Component
class ExtractionRulePublisher(
    private val snapshot: ExtractionRuleSnapshot,
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 올렸으면 true.
    fun publish(): Boolean {
        val expected = snapshot.build()
        val published = redisTemplate.opsForValue().get(KEY)
        if (published == expected) return false
        // 만료를 걸지 않는다. 이 Redis 는 메모리가 차면 만료 없는 키도 지우므로(allkeys-lru) 지워진 키는 아래 대조가 다시 채운다
        redisTemplate.opsForValue().set(KEY, expected)
        log.info("extraction rules published reason={} bytes={}", published?.let { "changed" } ?: "missing", expected.length)
        return true
    }

    // 실패해도 던지지 않는다. 저장은 이미 끝난 뒤라 호출자에게 알릴 실패가 아니고, 다음 대조가 다시 한다.
    fun publishQuietly() {
        try {
            publish()
        } catch (e: RuntimeException) {
            log.warn("extraction rules publish failed, next reconcile will retry cause={}", e.toString())
        }
    }

    // 키가 지워졌거나 다른 인스턴스의 저장을 놓친 경우를 이 주기로 바운드한다.
    @Scheduled(fixedDelay = RECONCILE_INTERVAL_MS)
    fun reconcile() = publishQuietly()

    companion object {
        internal val KEY: String = ExtractionRuleSet.getDescriptor().options.getExtension(ExtractionProto.redisKey)

        private const val RECONCILE_INTERVAL_MS = 300_000L
    }
}
