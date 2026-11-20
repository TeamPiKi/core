package com.depromeet.piki.common.event

import com.depromeet.piki.item.event.ItemParseRequested
import com.depromeet.piki.item.service.ItemParseRequestRelay
import com.depromeet.piki.support.IntegrationTestSupport
import org.awaitility.Awaitility.await
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class FailedPublicationResubmissionIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var failedPublicationResubmitter: FailedPublicationResubmitter

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `실패로 남은 발행 기록을 다시 보내면 저장된 이벤트로 리스너가 다시 실행되고 기록이 지워진다`() {
        val publicationId = UUID.randomUUID().toString()
        jdbcTemplate.update(
            "INSERT INTO EVENT_PUBLICATION (ID, LISTENER_ID, EVENT_TYPE, SERIALIZED_EVENT, PUBLICATION_DATE, STATUS, COMPLETION_ATTEMPTS) " +
                "VALUES (?, ?, ?, ?, NOW(6), 'FAILED', 1)",
            publicationId,
            ItemParseRequestRelay.LISTENER_ID,
            ItemParseRequested::class.java.name,
            "{\"requestId\":-1}",
        )

        failedPublicationResubmitter.resubmit()

        await().atMost(Duration.ofSeconds(5)).until { publicationCountOf(publicationId) == 0 }
        assertEquals(0, publicationCountOf(publicationId))
    }

    private fun publicationCountOf(publicationId: String): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM EVENT_PUBLICATION WHERE ID = ?", Int::class.java, publicationId) ?: 0
}
