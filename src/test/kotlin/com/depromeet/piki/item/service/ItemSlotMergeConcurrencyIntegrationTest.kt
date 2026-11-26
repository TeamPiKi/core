package com.depromeet.piki.item.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.depromeet.piki.image.domain.ProductImage
import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.item.domain.ParsePurpose
import com.depromeet.piki.item.domain.ParseRequestStatus
import com.depromeet.piki.item.repository.ItemJpaRepository
import com.depromeet.piki.item.repository.ItemParseOutboxRepository
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.deleteItems
import com.depromeet.piki.support.deleteParseOutboxOf
import com.depromeet.piki.support.deleteWishDataOf
import com.depromeet.piki.support.latestParseOutboxIdOf
import com.depromeet.piki.support.linkItem
import com.depromeet.piki.support.parseRequestStatusOf
import com.depromeet.piki.support.runningQueries
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.tournament.domain.TournamentItem
import com.depromeet.piki.tournament.repository.TournamentItemRepository
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.repository.UserJpaRepository
import com.depromeet.piki.wishlist.service.WishPersistenceService
import com.depromeet.piki.wishlist.service.WishlistService
import com.depromeet.piki.wishlist.service.dto.RecoverWishItemCommand
import com.depromeet.piki.wishlist.service.dto.RegisterWishFromUrlCommand
import com.depromeet.piki.wishlist.service.dto.UpdateWishMemoCommand
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 같은 아이템을 동시에 확정하면 늦은 쪽은 앞선 커밋을 못 본 스냅샷으로 판정한다. 유니크 거부 뒤 다시 실행해야 합쳐진다.
// 별도 트랜잭션의 동시 진행이 본질이라 @Transactional 없이 새 UUID 로 격리하고 finally 에서 정리한다
class ItemSlotMergeConcurrencyIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var wishPersistenceService: WishPersistenceService

    @Autowired private lateinit var parseResultConsumer: ParseResultConsumer

    @Autowired private lateinit var userJpaRepository: UserJpaRepository

    @Autowired private lateinit var itemJpaRepository: ItemJpaRepository

    @Autowired private lateinit var itemParseOutboxRepository: ItemParseOutboxRepository

    @Autowired private lateinit var tournamentItemRepository: TournamentItemRepository

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var wishlistService: WishlistService

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `같은 사용자의 두 위시가 이미 있는 아이템으로 동시에 확정되면 한 위시만 남고 메모가 합쳐진다`() {
        val finalUrl = "https://shop.example.com/products/merge-race-${UUID.randomUUID()}"
        itemJpaRepository.save(linkItem(finalUrl))

        assertWishesMergeUnderRace(finalUrl)
    }

    // 아이템 생성이 먼저 부딪혀 정규화 URL 유니크가 거부한다. 회수 대상으로 남지 않고 다시 기록해야 끝난다
    @Test
    fun `같은 사용자의 두 위시가 처음 보는 아이템으로 동시에 확정돼도 한 위시만 남고 메모가 합쳐진다`() {
        val finalUrl = "https://shop.example.com/products/merge-race-new-${UUID.randomUUID()}"

        assertWishesMergeUnderRace(finalUrl)
    }

    @Test
    fun `같은 토너먼트의 두 출전이 같은 아이템으로 동시에 확정되면 한 출전만 남는다`() {
        val finalUrl = "https://shop.example.com/products/merge-race-entry-${UUID.randomUUID()}"
        val item = itemJpaRepository.save(linkItem(finalUrl))
        val tournamentId = System.nanoTime()
        val owner = UUID.randomUUID()
        val entries =
            listOf("a", "b").map { suffix ->
                val entry = tournamentItemRepository.save(TournamentItem(tournamentId = tournamentId, userId = owner, itemId = null))
                itemParseOutboxRepository.save(
                    ItemParseOutbox.of(entry.slot, ParsePurpose.REGISTER, ParseInput.Link(ProductLink.parse("https://short.example.com/$suffix-$tournamentId"))),
                )
                entry.slot
            }
        try {
            confirmTogether(entries, finalUrl)

            assertEquals(listOf(ParseRequestStatus.SUCCEEDED, ParseRequestStatus.SUCCEEDED), entries.map { jdbcTemplate.parseRequestStatusOf(it) })

            val live =
                jdbcTemplate.queryForList(
                    "SELECT item_id FROM tournament_items WHERE tournament_id = ? AND deleted_at IS NULL",
                    Long::class.java,
                    tournamentId,
                )
            assertEquals(listOf(item.getId()), live)
        } finally {
            jdbcTemplate.deleteParseOutboxOf(entries)
            jdbcTemplate.update("DELETE FROM tournament_items WHERE tournament_id = ?", tournamentId)
            jdbcTemplate.deleteItems(listOf(item.getId()))
        }
    }

    // 파싱 성공이 아이템 잠금을 쥔 상태를 SQL 로 재현한다. 사용자 입력이 그 잠금을 기다린 뒤에 커밋해야 사용자 입력의 스냅샷이 커밋 전으로 고정된다
    @Test
    fun `같은 상품을 확정 중인 위시가 있을 때 실패 위시를 사용자 입력으로 살리면 다시 실행해 그 위시에 합쳐진다`() {
        val userId = UUID.randomUUID()
        userJpaRepository.save(User(id = userId, nickname = "merge", profileImage = "https://cdn.example.com/o.jpg", identityType = IdentityType.MEMBER))
        val finalUrl = "https://shop.example.com/products/merge-race-input-$userId"
        val item = itemJpaRepository.save(linkItem(finalUrl))
        val retryLogs = ListAppender<ILoggingEvent>().apply { start() }
        val retryLogger = LoggerFactory.getLogger(UniqueConflictRetry::class.java) as Logger
        retryLogger.addAppender(retryLogs)
        val confirmer = Executors.newSingleThreadExecutor()
        try {
            val failed = wishPersistenceService.persist(RegisterWishFromUrlCommand(userId, ProductLink.parse(finalUrl))).wish
            jdbcTemplate.update("UPDATE item_parse_outbox SET status = 'FAILED' WHERE slot_type = 'WISH' AND slot_id = ?", failed.getId())
            val holder = wishPersistenceService.persist(RegisterWishFromUrlCommand(userId, ProductLink.parse("https://short.example.com/input-$userId"))).wish
            val holderFilled = CountDownLatch(1)
            val confirming =
                confirmer.submit {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        jdbcTemplate.queryForObject("SELECT id FROM items WHERE id = ? FOR UPDATE", Long::class.java, item.getId())
                        jdbcTemplate.update("UPDATE wishes SET item_id = ? WHERE id = ?", item.getId(), holder.getId())
                        holderFilled.countDown()
                        await().atMost(Duration.ofSeconds(5)).until { jdbcTemplate.runningQueries("%from items %for update%") > 0 }
                    }
                }
            assertTrue(holderFilled.await(5, TimeUnit.SECONDS))

            val result =
                wishlistService.recoverWishItem(
                    RecoverWishItemCommand(
                        userId = userId,
                        wishId = failed.getId(),
                        name = "직접 입력한 이름",
                        price = 50_000,
                        originalPrice = null,
                        currency = "KRW",
                        image = ProductImage.of(byteArrayOf(1, 2, 3), "image/png"),
                        memo = null,
                    ),
                )
            confirming.get(5, TimeUnit.SECONDS)

            assertEquals(holder.getId(), result.wish.getId())
            val live = jdbcTemplate.queryForList("SELECT id FROM wishes WHERE user_id = ? AND deleted_at IS NULL", Long::class.java, uuidToBytes(userId))
            assertEquals(listOf(holder.getId()), live)
            assertTrue(retryLogs.list.any { it.formattedMessage.startsWith("item.confirm.retry wish=${failed.getId()}") })
        } finally {
            confirmer.shutdownNow()
            retryLogger.detachAppender(retryLogs)
            jdbcTemplate.deleteWishDataOf(userId)
            jdbcTemplate.deleteItems(listOf(item.getId()))
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(userId))
        }
    }

    private fun assertWishesMergeUnderRace(finalUrl: String) {
        val userId = UUID.randomUUID()
        userJpaRepository.save(User(id = userId, nickname = "merge", profileImage = "https://cdn.example.com/o.jpg", identityType = IdentityType.MEMBER))
        try {
            val slots =
                listOf("선물용", "사이즈 M").mapIndexed { index, memo ->
                    val url = "https://short.example.com/$index-$userId"
                    val wish = wishPersistenceService.persist(RegisterWishFromUrlCommand(userId, ProductLink.parse(url))).wish
                    wishPersistenceService.updateMemo(UpdateWishMemoCommand(userId = userId, wishId = wish.getId(), memo = memo))
                    wish.slot
                }
            confirmTogether(slots, finalUrl)

            assertEquals(listOf(ParseRequestStatus.SUCCEEDED, ParseRequestStatus.SUCCEEDED), slots.map { jdbcTemplate.parseRequestStatusOf(it) })

            val live =
                jdbcTemplate.queryForList(
                    "SELECT id FROM wishes WHERE user_id = ? AND deleted_at IS NULL",
                    Long::class.java,
                    uuidToBytes(userId),
                )
            assertEquals(1, live.size, "같은 아이템을 가진 살아 있는 위시는 하나")
            val memos = jdbcTemplate.queryForList("SELECT content FROM wish_memos WHERE wish_id = ?", String::class.java, live.single())
            assertEquals(setOf("선물용", "사이즈 M"), memos.toSet())
        } finally {
            jdbcTemplate.deleteWishDataOf(userId)
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(userId))
        }
    }

    // 한 서버의 소비자는 결과를 하나씩 반영한다. 서버가 여럿이거나 이어받기가 겹칠 때 두 확정이 함께 진행된다
    private fun confirmTogether(
        slots: List<ItemSlot>,
        finalUrl: String,
    ) {
        val executor = Executors.newFixedThreadPool(slots.size)
        val ready = CountDownLatch(slots.size)
        val start = CountDownLatch(1)
        try {
            val confirmations =
                slots.map { slot ->
                    val result = successResultOf(jdbcTemplate.latestParseOutboxIdOf(slot), finalUrl)
                    executor.submit {
                        ready.countDown()
                        start.await()
                        parseResultConsumer.onMessage(result)
                    }
                }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            confirmations.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun successResultOf(
        requestId: Long,
        finalUrl: String,
    ): MapRecord<String, String, String> =
        StreamRecords
            .mapBacked<String, String, String>(
                mapOf(
                    "requestId" to requestId.toString(),
                    "code" to ParseResultMessage.CODE_OK,
                    "name" to "합칠 상품",
                    "currentPrice" to "10000",
                    "currency" to "KRW",
                    "imageUrl" to "https://img.example.com/m.png",
                    "finalUrl" to finalUrl,
                ),
            ).withStreamKey(ParseResultMessage.STREAM)
            .withId(RecordId.of(requestId, 0))
}
