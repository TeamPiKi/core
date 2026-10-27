package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.completeLatest
import com.depromeet.piki.support.failLatest
import com.depromeet.piki.support.itemVersion
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.repository.UserJpaRepository
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.service.WishPersistenceService
import com.depromeet.piki.wishlist.service.dto.RefreshWishItemCommand
import com.depromeet.piki.wishlist.service.dto.RegisterWishFromUrlCommand
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Transactional
class ItemCardIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var itemCardService: ItemCardService

    @Autowired private lateinit var wishPersistenceService: WishPersistenceService

    @Autowired private lateinit var itemParsingService: ItemParsingService

    @Autowired private lateinit var itemSnapshotRepository: ItemSnapshotRepository

    @Autowired private lateinit var entityManager: EntityManager

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var userJpaRepository: UserJpaRepository

    @Test
    fun `아이템이 없고 최근 요청이 진행 중이면 PARSING 이고 기다리는 URL 을 싣는다`() {
        val url = "https://www.musinsa.com/products/3000001"
        val wish = wishPersistenceService.persist(RegisterWishFromUrlCommand(newMember(), ProductLink.parse(url))).wish

        val card = itemCardService.cardOf(wish.cardQuery())

        assertEquals(ItemStatus.PARSING, card.status)
        assertNull(card.itemId)
        assertNull(card.display)
        assertFalse(card.refreshing)
        assertEquals(ProductLink.parse(url), card.sourceLink)
    }

    @Test
    fun `아이템이 없고 최근 요청이 실패했으면 FAILED 다`() {
        val wish = wishPersistenceService.persist(RegisterWishFromUrlCommand(newMember(), ProductLink.parse("https://www.musinsa.com/products/3000002"))).wish
        itemParsingService.failLatest(entityManager, wish.slot)

        val card = itemCardService.cardOf(wish.cardQuery())

        assertEquals(ItemStatus.FAILED, card.status)
        assertNull(card.itemId)
    }

    @Test
    fun `아이템이 있으면 표시 버전의 값 개수로 READY 와 INCOMPLETE 가 갈린다`() {
        val ready = parsedWish("https://www.musinsa.com/products/3000003", ProductSnapshot(name = "상품", price = 1_000, imageUrl = IMAGE))
        val incomplete = parsedWish("https://www.musinsa.com/products/3000004", ProductSnapshot(name = "상품", price = 1_000))

        val cards = itemCardService.cardsOf(listOf(ready.cardQuery(), incomplete.cardQuery()))

        val readyCard = cards.getValue(ready.cardQuery())
        assertEquals(ItemStatus.READY, readyCard.status)
        assertEquals(ready.itemId, readyCard.itemId)
        assertEquals("상품", readyCard.display?.name)
        assertEquals(ItemStatus.INCOMPLETE, cards.getValue(incomplete.cardQuery()).status)
    }

    @Test
    fun `아이템이 있는 카드의 새로고침이 진행 중이면 상태는 그대로 두고 refreshing 이다`() {
        val owner = newMember()
        val wish = parsedWish("https://www.musinsa.com/products/3000005", ProductSnapshot(name = "상품", price = 1_000, imageUrl = IMAGE), owner)
        wishPersistenceService.refresh(RefreshWishItemCommand(owner, wish.getId()))

        val card = itemCardService.cardOf(wish.cardQuery())

        assertEquals(ItemStatus.READY, card.status)
        assertTrue(card.refreshing)
    }

    @Test
    fun `기준 시각이 있으면 그 뒤에 생긴 버전을 빼고 새로고침 진행도 보이지 않는다`() {
        val owner = newMember()
        val wish = parsedWish("https://www.musinsa.com/products/3000006", ProductSnapshot(name = "시작 전 값", price = 1_000, imageUrl = IMAGE), owner)
        val itemId = assertNotNull(wish.itemId)
        val later = itemSnapshotRepository.save(itemVersion(itemId = itemId, name = "시작 뒤 값", createdBy = owner))
        entityManager.flush()
        jdbcTemplate.update("UPDATE item_snapshots SET created_at = ? WHERE id = ?", Timestamp.valueOf(LocalDateTime.now().plusHours(1)), later.getId())
        entityManager.clear()
        wishPersistenceService.refresh(RefreshWishItemCommand(owner, wish.getId()))

        val card = itemCardService.cardOf(wish.cardQuery().copy(asOf = LocalDateTime.now()))

        assertEquals("시작 전 값", card.display?.name)
        assertFalse(card.refreshing)
        assertEquals("시작 뒤 값", itemCardService.cardOf(wish.cardQuery()).display?.name)
    }

    private fun parsedWish(
        url: String,
        extracted: ProductSnapshot,
        owner: UUID = newMember(),
    ): Wish {
        val wish = wishPersistenceService.persist(RegisterWishFromUrlCommand(owner, ProductLink.parse(url))).wish
        itemParsingService.completeLatest(entityManager, wish.slot, extracted)
        return wish
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
        private const val IMAGE = "https://img.example.com/a.png"
    }
}
