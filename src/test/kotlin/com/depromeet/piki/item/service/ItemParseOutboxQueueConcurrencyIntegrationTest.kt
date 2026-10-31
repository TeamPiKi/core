package com.depromeet.piki.item.service

import com.depromeet.piki.common.outbox.OutboxJpaRepository
import com.depromeet.piki.common.outbox.OutboxQueue
import com.depromeet.piki.common.outbox.OutboxQueueContract
import com.depromeet.piki.image.domain.ProductImageKey
import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.item.domain.ParsePurpose
import com.depromeet.piki.support.IntegrationTestSupport
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import kotlin.random.Random

class ItemParseOutboxQueueConcurrencyIntegrationTest : IntegrationTestSupport(), OutboxQueueContract<ItemParseOutbox> {
    @Autowired override lateinit var queue: OutboxQueue<ItemParseOutbox>

    @Autowired override lateinit var jpaRepository: OutboxJpaRepository<ItemParseOutbox>

    @Autowired override lateinit var transactionManager: PlatformTransactionManager

    @Autowired override lateinit var jdbcTemplate: JdbcTemplate

    override val table = "item_parse_outbox"

    override fun newPending(): ItemParseOutbox =
        ItemParseOutbox.of(ItemSlot.wish(Random.nextLong(1, Long.MAX_VALUE)), ParsePurpose.REGISTER, ParseInput.Image(ProductImageKey.newRaw("jpg")))
}
