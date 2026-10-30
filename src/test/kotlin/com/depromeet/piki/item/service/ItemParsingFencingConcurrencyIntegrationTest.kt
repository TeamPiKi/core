package com.depromeet.piki.item.service

import com.depromeet.piki.common.config.AsyncConfig
import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemParseOutboxStatus
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.item.domain.ParsePurpose
import com.depromeet.piki.item.repository.ItemParseOutboxRepository
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.product.service.remote.ProductExtractorException
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubLinkSnapshotExtractor
import com.depromeet.piki.support.deleteWishDataOf
import com.depromeet.piki.support.parseOutboxStatusOf
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.support.wishItemIdOf
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.repository.UserJpaRepository
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.repository.WishJpaRepository
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.sql.Timestamp
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// 옛 워커와 새 워커가 같은 요청을 동시에 쥔 상황이 본질이라 @Transactional 없이 새 UUID 로 격리하고 finally 에서 정리한다
class ItemParsingFencingConcurrencyIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var userJpaRepository: UserJpaRepository

    @Autowired private lateinit var wishJpaRepository: WishJpaRepository

    @Autowired private lateinit var itemParseOutboxRepository: ItemParseOutboxRepository

    @Autowired private lateinit var itemParsingScheduler: ItemParsingScheduler

    @Autowired private lateinit var stubLinkSnapshotExtractor: StubLinkSnapshotExtractor

    @Autowired
    @Qualifier(AsyncConfig.ITEM_PARSING_EXECUTOR)
    private lateinit var itemParsingExecutor: ThreadPoolTaskExecutor

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    @Timeout(30)
    fun `회수 뒤 다시 집힌 요청에 옛 워커의 늦은 실패가 기록되지 않는다`() {
        val userId = UUID.randomUUID()
        userJpaRepository.save(
            User(id = userId, nickname = "fencing", profileImage = "https://cdn.example.com/o.jpg", identityType = IdentityType.MEMBER),
        )
        val url = "https://shop.example.com/products/fencing-${UUID.randomUUID()}"
        val wish = wishJpaRepository.save(Wish(userId = userId, itemId = null))
        val outbox = itemParseOutboxRepository.save(ItemParseOutbox.of(wish.slot, ParsePurpose.REGISTER, ParseInput.Link(ProductLink.parse(url))))

        val calls = AtomicInteger(0)
        val oldEntered = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val newEntered = CountDownLatch(1)
        val releaseNew = CountDownLatch(1)
        stubLinkSnapshotExtractor.build = { link ->
            check(link.toString() == url) { "이 테스트의 요청만 파싱한다: $link" }
            if (calls.incrementAndGet() == 1) {
                oldEntered.countDown()
                releaseOld.await(10, TimeUnit.SECONDS)
                throw ProductExtractorException.transientFailure(null)
            }
            newEntered.countDown()
            releaseNew.await(10, TimeUnit.SECONDS)
            ProductSnapshot(name = "펜싱 상품", price = 10_000, currency = "KRW", imageUrl = "https://img.example.com/a.png")
        }

        try {
            await().atMost(Duration.ofSeconds(30)).until { itemParsingExecutor.activeCount == 0 }
            itemParsingScheduler.dispatch()
            assertTrue(oldEntered.await(5, TimeUnit.SECONDS), "옛 워커가 추출에 들어가야 한다")

            // 옛 워커가 추출에서 2분 넘게 멈춘 상황
            jdbcTemplate.update(
                "UPDATE item_parse_outbox SET updated_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(3)),
                outbox.getId(),
            )
            itemParsingScheduler.recover()
            itemParsingScheduler.dispatch()
            assertTrue(newEntered.await(5, TimeUnit.SECONDS), "회수된 요청을 새 워커가 다시 집어야 한다")

            releaseOld.countDown()
            await().atMost(Duration.ofSeconds(5)).until { itemParsingExecutor.activeCount == 1 }
            assertEquals(ItemParseOutboxStatus.PROCESSING, jdbcTemplate.parseOutboxStatusOf(wish.slot))

            releaseNew.countDown()
            await().atMost(Duration.ofSeconds(5)).until { jdbcTemplate.parseOutboxStatusOf(wish.slot) == ItemParseOutboxStatus.SUCCEEDED }
            assertNotNull(jdbcTemplate.wishItemIdOf(wish.getId()))
            // 알림은 커밋 뒤 비동기로 저장되므로 정리 전에 도착을 기다린다
            await().atMost(Duration.ofSeconds(5)).until { notificationsOf(userId, "ITEM_PARSING_COMPLETED") == 1 }
        } finally {
            releaseOld.countDown()
            releaseNew.countDown()
            runCatching { await().atMost(Duration.ofSeconds(5)).until { itemParsingExecutor.activeCount == 0 } }
            jdbcTemplate.update("DELETE FROM notifications WHERE user_id = ?", uuidToBytes(userId))
            jdbcTemplate.deleteWishDataOf(userId)
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(userId))
        }
    }

    private fun notificationsOf(
        userId: UUID,
        type: String,
    ): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = ?",
            Int::class.java,
            uuidToBytes(userId),
            type,
        ) ?: 0
}
