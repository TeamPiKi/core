package com.depromeet.piki.item.service

import com.depromeet.piki.common.outbox.OutboxStatus
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.event.ItemParsingCompleted
import com.depromeet.piki.item.event.ItemParsingFailed
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.parseOutboxStatusOf
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.repository.UserJpaRepository
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.service.WishPersistenceService
import com.depromeet.piki.wishlist.service.dto.RegisterWishFromUrlCommand
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

@Transactional
@RecordApplicationEvents
class ItemParseResultIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var applicationEvents: ApplicationEvents

    @Autowired private lateinit var wishPersistenceService: WishPersistenceService

    @Autowired private lateinit var itemParsingService: ItemParsingService

    @Autowired private lateinit var parseRequestDeadline: ParseRequestDeadline

    @Autowired private lateinit var userJpaRepository: UserJpaRepository

    @Autowired private lateinit var entityManager: EntityManager

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `진행 중인 요청에 추출 결과를 반영하면 요청이 성공으로 닫히고 파싱 완료 사실을 발행한다`() {
        val wish = registerWish("https://www.musinsa.com/products/8300001")

        val settlement = itemParsingService.applyExtracted(requestIdOf(wish), snapshot())

        assertEquals(ExtractionSettlement.Applied(ItemStatus.READY), settlement)
        assertEquals(OutboxStatus.SUCCEEDED, statusOf(wish))
        assertEquals(listOf(wish.slot), applicationEvents.stream(ItemParsingCompleted::class.java).map { it.slot }.toList())
    }

    @Test
    fun `이미 닫힌 요청에 추출 결과가 다시 오면 반영하지 않는다`() {
        val wish = registerWish("https://www.musinsa.com/products/8300002")
        val requestId = requestIdOf(wish)
        itemParsingService.applyExtracted(requestId, snapshot())

        val settlement = itemParsingService.applyExtracted(requestId, snapshot())

        assertEquals(ExtractionSettlement.Superseded, settlement)
        assertEquals(1, applicationEvents.stream(ItemParsingCompleted::class.java).count())
    }

    @Test
    fun `진행 중인 요청에 실패를 반영하면 요청이 실패로 닫히고 파싱 실패 사실을 발행한다`() {
        val wish = registerWish("https://www.musinsa.com/products/8300003")

        val applied = itemParsingService.applyFailed(requestIdOf(wish))

        assertEquals(true, applied)
        assertEquals(OutboxStatus.FAILED, statusOf(wish))
        assertEquals(listOf(wish.slot), applicationEvents.stream(ItemParsingFailed::class.java).map { it.slot }.toList())
    }

    @Test
    fun `실패로 닫힌 요청에 뒤늦은 추출 결과가 오면 반영하지 않는다`() {
        val wish = registerWish("https://www.musinsa.com/products/8300004")
        val requestId = requestIdOf(wish)
        itemParsingService.applyFailed(requestId)

        val settlement = itemParsingService.applyExtracted(requestId, snapshot())

        assertEquals(ExtractionSettlement.Superseded, settlement)
        assertEquals(OutboxStatus.FAILED, statusOf(wish))
    }

    @Test
    fun `기한이 지나도록 결과가 없는 요청은 실패로 닫히고 파싱 실패 사실을 발행한다`() {
        val wish = registerWish("https://www.musinsa.com/products/8300005")
        jdbcTemplate.update("UPDATE item_parse_outbox SET created_at = created_at - INTERVAL 10 MINUTE WHERE id = ?", requestIdOf(wish))
        entityManager.clear()

        parseRequestDeadline.closeOverdue(Duration.ofMinutes(7))

        assertEquals(OutboxStatus.FAILED, statusOf(wish))
        assertEquals(listOf(wish.slot), applicationEvents.stream(ItemParsingFailed::class.java).map { it.slot }.toList())
    }

    @Test
    fun `기한 안의 요청은 닫지 않는다`() {
        val wish = registerWish("https://www.musinsa.com/products/8300006")

        parseRequestDeadline.closeOverdue(Duration.ofMinutes(7))

        assertEquals(OutboxStatus.PENDING, statusOf(wish))
    }

    private fun registerWish(url: String): Wish {
        val userId = UUID.randomUUID()
        userJpaRepository.save(
            User(
                id = userId,
                nickname = "곰${userId.toString().take(5)}",
                profileImage = "https://cdn.example.com/p.jpg",
                identityType = IdentityType.MEMBER,
            ),
        )
        return wishPersistenceService.persist(RegisterWishFromUrlCommand(userId, ProductLink.parse(url))).wish
    }

    private fun requestIdOf(wish: Wish): Long {
        entityManager.flush()
        return jdbcTemplate.queryForObject(
            "SELECT MAX(id) FROM item_parse_outbox WHERE slot_type = ? AND slot_id = ?",
            Long::class.java,
            wish.slot.type.name,
            wish.slot.id,
        ) ?: error("${wish.slot} 의 파싱 요청 행이 없다")
    }

    private fun statusOf(wish: Wish): OutboxStatus {
        entityManager.flush()
        return jdbcTemplate.parseOutboxStatusOf(wish.slot)
    }

    private fun snapshot(): ProductSnapshot =
        ProductSnapshot(
            name = "결과 상품",
            price = 10_000,
            currency = "KRW",
            imageUrl = "https://img.example.com/r.png",
        )
}
