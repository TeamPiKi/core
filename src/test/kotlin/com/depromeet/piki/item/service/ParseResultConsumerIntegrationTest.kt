package com.depromeet.piki.item.service

import com.depromeet.piki.common.outbox.OutboxStatus
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.product.service.remote.ProductExtractorException
import com.depromeet.piki.support.FakeExtractor
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubLinkSnapshotExtractor
import com.depromeet.piki.support.deleteWishDataOf
import com.depromeet.piki.support.latestParseOutboxIdOf
import com.depromeet.piki.support.parseOutboxStatusOf
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.support.wishItemIdOf
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.repository.UserJpaRepository
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.service.WishPersistenceService
import com.depromeet.piki.wishlist.service.dto.RegisterWishFromUrlCommand
import org.awaitility.Awaitility.await
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.stream.StreamMessageListenerContainer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ParseResultConsumerIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var wishPersistenceService: WishPersistenceService

    @Autowired private lateinit var userJpaRepository: UserJpaRepository

    @Autowired private lateinit var transactionTemplate: TransactionTemplate

    @Autowired private lateinit var redis: StringRedisTemplate

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var fakeExtractor: FakeExtractor

    @Autowired private lateinit var stubLinkSnapshotExtractor: StubLinkSnapshotExtractor

    @Autowired private lateinit var parseResultReclaimer: ParseResultReclaimer

    @Autowired private lateinit var parseResultListenerContainer: StreamMessageListenerContainer<String, MapRecord<String, String, String>>

    @Test
    fun `링크로 위시를 담으면 요청 스트림과 결과 스트림을 거쳐 상품이 채워진다`() {
        val userId = newMember()
        stubLinkSnapshotExtractor.build = { ProductSnapshot(name = "왕복 상품", price = 15_000, currency = "KRW", imageUrl = "https://img.example.com/w.png") }
        val wish = registerWish(userId, "https://www.musinsa.com/products/8400006")

        fakeExtractor.awaitTicking { jdbcTemplate.parseOutboxStatusOf(wish.slot) == OutboxStatus.SUCCEEDED }

        assertNotNull(jdbcTemplate.wishItemIdOf(wish.getId()))
        deleteMemberData(userId)
    }

    @Test
    fun `추출이 차단 도메인으로 실패하면 요청 스트림과 결과 스트림을 거쳐 요청이 실패로 닫힌다`() {
        val userId = newMember()
        stubLinkSnapshotExtractor.build = { throw ProductExtractorException.blockedDomain() }
        val wish = registerWish(userId, "https://www.musinsa.com/products/8400007")

        fakeExtractor.awaitTicking { jdbcTemplate.parseOutboxStatusOf(wish.slot) == OutboxStatus.FAILED }

        assertNull(jdbcTemplate.wishItemIdOf(wish.getId()))
        deleteMemberData(userId)
    }

    @Test
    fun `결과 스트림에 성공 결과가 오면 요청이 성공으로 닫히고 위시에 상품이 채워진다`() {
        val userId = newMember()
        val wish = registerWish(userId, "https://www.musinsa.com/products/8400001")

        publishResult(
            mapOf(
                "requestId" to jdbcTemplate.latestParseOutboxIdOf(wish.slot).toString(),
                "code" to "OK",
                "name" to "스트림 상품",
                "currentPrice" to "12000",
                "currency" to "KRW",
                "imageUrl" to "https://img.example.com/s.png",
            ),
        )

        await().atMost(TIMEOUT).until { jdbcTemplate.parseOutboxStatusOf(wish.slot) == OutboxStatus.SUCCEEDED }
        assertNotNull(jdbcTemplate.wishItemIdOf(wish.getId()))
        deleteMemberData(userId)
    }

    @Test
    fun `결과 스트림에 실패 code 가 오면 요청이 실패로 닫힌다`() {
        val userId = newMember()
        val wish = registerWish(userId, "https://www.musinsa.com/products/8400002")

        publishResult(mapOf("requestId" to jdbcTemplate.latestParseOutboxIdOf(wish.slot).toString(), "code" to "BLOCKED_DOMAIN"))

        await().atMost(TIMEOUT).until { jdbcTemplate.parseOutboxStatusOf(wish.slot) == OutboxStatus.FAILED }
        assertNull(jdbcTemplate.wishItemIdOf(wish.getId()))
        deleteMemberData(userId)
    }

    @Test
    fun `code 가 없는 결과는 성공으로 보지 않고 요청을 실패로 닫는다`() {
        val userId = newMember()
        val wish = registerWish(userId, "https://www.musinsa.com/products/8400003")

        publishResult(mapOf("requestId" to jdbcTemplate.latestParseOutboxIdOf(wish.slot).toString(), "name" to "code 없는 상품", "currentPrice" to "1000"))

        await().atMost(TIMEOUT).until { jdbcTemplate.parseOutboxStatusOf(wish.slot) == OutboxStatus.FAILED }
        deleteMemberData(userId)
    }

    @Test
    fun `처리한 결과는 대기 목록과 스트림에서 모두 지운다`() {
        val userId = newMember()
        val wish = registerWish(userId, "https://www.musinsa.com/products/8400004")
        val requestId = jdbcTemplate.latestParseOutboxIdOf(wish.slot).toString()

        publishResult(mapOf("requestId" to requestId, "code" to "BLOCKED_DOMAIN"))

        await().atMost(TIMEOUT).until { jdbcTemplate.parseOutboxStatusOf(wish.slot) == OutboxStatus.FAILED }
        await().atMost(TIMEOUT).until { pendingCount() == 0L && streamEntriesOf(requestId).isEmpty() }
        assertEquals(0L, pendingCount())
        assertEquals(emptyList(), streamEntriesOf(requestId))
        deleteMemberData(userId)
    }

    @Test
    fun `다른 소비자가 꺼낸 뒤 확인 응답 없이 사라진 결과를 이어받아 반영한다`() {
        val userId = newMember()
        val wish = registerWish(userId, "https://www.musinsa.com/products/8400005")
        val requestId = jdbcTemplate.latestParseOutboxIdOf(wish.slot).toString()
        takeWithoutAcknowledging(mapOf("requestId" to requestId, "code" to "BLOCKED_DOMAIN"))
        assertEquals(OutboxStatus.PENDING, jdbcTemplate.parseOutboxStatusOf(wish.slot))

        parseResultReclaimer.reclaim(Duration.ZERO)

        assertEquals(OutboxStatus.FAILED, jdbcTemplate.parseOutboxStatusOf(wish.slot))
        assertEquals(emptyList(), streamEntriesOf(requestId))
        deleteMemberData(userId)
    }

    private fun takeWithoutAcknowledging(fields: Map<String, String>) {
        parseResultListenerContainer.stop()
        try {
            publishResult(fields)
            redis
                .opsForStream<String, String>()
                .read(Consumer.from(ParseResultConsumer.GROUP, "gone-${UUID.randomUUID()}"), StreamOffset.create(ParseResultMessage.STREAM, ReadOffset.lastConsumed()))
        } finally {
            parseResultListenerContainer.start()
        }
    }

    private fun publishResult(fields: Map<String, String>) {
        redis.opsForStream<String, String>().add(ParseResultMessage.STREAM, fields)
    }

    private fun streamEntriesOf(requestId: String): List<Map<String, String>> =
        redis
            .opsForStream<String, String>()
            .range(ParseResultMessage.STREAM, Range.unbounded())
            .orEmpty()
            .map { it.value }
            .filter { it["requestId"] == requestId }

    private fun pendingCount(): Long =
        redis.opsForStream<String, String>().pending(ParseResultMessage.STREAM, ParseResultConsumer.GROUP)?.totalPendingMessages ?: 0L

    private fun registerWish(
        userId: UUID,
        url: String,
    ): Wish =
        transactionTemplate.execute {
            wishPersistenceService.persist(RegisterWishFromUrlCommand(userId, ProductLink.parse(url))).wish
        } ?: error("위시를 등록하지 못했다")

    private fun deleteMemberData(userId: UUID) {
        jdbcTemplate.deleteWishDataOf(userId)
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(userId))
    }

    private fun newMember(): UUID {
        val userId = UUID.randomUUID()
        userJpaRepository.save(
            User(
                id = userId,
                nickname = "곰${userId.toString().take(5)}",
                profileImage = "https://cdn.example.com/p.jpg",
                identityType = IdentityType.MEMBER,
            ),
        )
        return userId
    }

    companion object {
        private val TIMEOUT = Duration.ofSeconds(5)
    }
}
