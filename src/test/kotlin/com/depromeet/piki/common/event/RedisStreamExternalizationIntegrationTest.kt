package com.depromeet.piki.common.event

import com.depromeet.piki.item.event.ItemParseRequested
import com.depromeet.piki.product.domain.ParseInputType
import com.depromeet.piki.support.IntegrationTestSupport
import org.awaitility.Awaitility.await
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Range
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.test.Test
import kotlin.test.assertEquals

class RedisStreamExternalizationIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var eventPublisher: ApplicationEventPublisher

    @Autowired private lateinit var transactionTemplate: TransactionTemplate

    @Autowired private lateinit var failedPublicationResubmitter: FailedPublicationResubmitter

    @Autowired private lateinit var redis: StringRedisTemplate

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `커밋된 파싱 요청 사실은 요청 스트림에 필드로 실리고 발행 기록이 지워진다`() {
        val event = ItemParseRequested(newRequestId(), ParseInputType.LINK, "https://www.musinsa.com/products/8200001")

        transactionTemplate.executeWithoutResult { eventPublisher.publishEvent(event) }

        await().atMost(TIMEOUT).until { streamEntriesOf(event.requestId).isNotEmpty() && publicationCountOf(event.requestId) == 0 }
        val expected = mapOf("requestId" to event.requestId.toString(), "inputType" to "LINK", "inputValue" to event.inputValue)
        assertEquals(listOf(expected), streamEntriesOf(event.requestId))
    }

    @Test
    fun `실패로 남은 발행 기록을 다시 보내면 저장된 이벤트가 요청 스트림에 실리고 기록이 지워진다`() {
        val requestId = newRequestId()
        val listenerId = externalizerListenerId()
        jdbcTemplate.update(
            "INSERT INTO EVENT_PUBLICATION (ID, LISTENER_ID, EVENT_TYPE, SERIALIZED_EVENT, PUBLICATION_DATE, STATUS, COMPLETION_ATTEMPTS) " +
                "VALUES (?, ?, ?, ?, NOW(6), 'FAILED', 1)",
            UUID.randomUUID().toString(),
            listenerId,
            ItemParseRequested::class.java.name,
            """{"requestId":$requestId,"inputType":"LINK","inputValue":"https://www.musinsa.com/products/8200002"}""",
        )

        failedPublicationResubmitter.resubmit()

        await().atMost(TIMEOUT).until { streamEntriesOf(requestId).isNotEmpty() && publicationCountOf(requestId) == 0 }
        assertEquals(1, streamEntriesOf(requestId).size)
    }

    private fun externalizerListenerId(): String {
        val event = ItemParseRequested(newRequestId(), ParseInputType.LINK, "https://www.musinsa.com/products/8200003")
        return transactionTemplate.execute {
            eventPublisher.publishEvent(event)
            jdbcTemplate.queryForObject(
                "SELECT LISTENER_ID FROM EVENT_PUBLICATION WHERE SERIALIZED_EVENT LIKE ?",
                String::class.java,
                "%\"requestId\":${event.requestId},%",
            )
        } ?: error("발행 기록이 없다")
    }

    private fun streamEntriesOf(requestId: Long): List<Map<String, String>> =
        redis
            .opsForStream<String, String>()
            .range(ItemParseRequested.STREAM, Range.unbounded())
            .orEmpty()
            .map { it.value }
            .filter { it["requestId"] == requestId.toString() }

    private fun publicationCountOf(requestId: Long): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM EVENT_PUBLICATION WHERE SERIALIZED_EVENT LIKE ?",
            Int::class.java,
            "%\"requestId\":$requestId,%",
        ) ?: 0

    private fun newRequestId(): Long = ThreadLocalRandom.current().nextLong(1_000_000_000_000L, 9_000_000_000_000L)

    companion object {
        private val TIMEOUT = Duration.ofSeconds(5)
    }
}
