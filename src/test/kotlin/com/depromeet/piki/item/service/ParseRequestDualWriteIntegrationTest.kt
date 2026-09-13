package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.domain.ParseRequestStatus
import com.depromeet.piki.item.domain.ParseTrigger
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ParseRequestRepository
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.support.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Transactional
class ParseRequestDualWriteIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var parsingEnqueuer: ParsingEnqueuer

    @Autowired private lateinit var itemRepository: ItemRepository

    @Autowired private lateinit var parseRequestRepository: ParseRequestRepository

    @Test
    fun `적재하면 PENDING snapshot 과 함께 PENDING 요청이 남고 요청이 그 버전을 가리킨다`() {
        val requester = UUID.randomUUID()
        val link = ProductLink.parse("https://shop.example.com/products/dual-${UUID.randomUUID()}")
        val itemId = itemRepository.save(Item(link)).getId()

        val snapshot = parsingEnqueuer.enqueue(itemId, requester, ParseTrigger.REFRESH)

        assertEquals(ItemStatus.PENDING, snapshot.status)
        val request = parseRequestRepository.findBySnapshotId(snapshot.getId()) ?: error("요청 행이 없다")
        assertEquals(ParseRequestStatus.PENDING, request.status)
        assertEquals(itemId, request.itemId)
        assertEquals(requester, request.requestedBy)
        assertEquals(ParseTrigger.REFRESH, request.triggerType)
        assertEquals(0, request.attemptCount)
        assertNull(request.claimedAt)
        assertNull(request.finishedAt)
    }
}
