package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemParseOutboxStatus
import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.item.domain.ParsePurpose
import com.depromeet.piki.item.repository.ItemJpaRepository
import com.depromeet.piki.item.repository.ItemParseOutboxRepository
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubLinkSnapshotExtractor
import com.depromeet.piki.support.awaitTicking
import com.depromeet.piki.support.deleteItems
import com.depromeet.piki.support.deleteParseOutboxOf
import com.depromeet.piki.support.deleteWishDataOf
import com.depromeet.piki.support.linkItem
import com.depromeet.piki.support.parseOutboxStatusOf
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.tournament.domain.TournamentItem
import com.depromeet.piki.tournament.repository.TournamentItemRepository
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.repository.UserJpaRepository
import com.depromeet.piki.wishlist.service.WishPersistenceService
import com.depromeet.piki.wishlist.service.dto.RegisterWishFromUrlCommand
import com.depromeet.piki.wishlist.service.dto.UpdateWishMemoCommand
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

// 두 파싱이 같은 아이템을 동시에 확정하면 늦은 쪽은 앞선 커밋을 못 본 스냅샷으로 판정한다. 유니크 거부 뒤 다시 기록해야 합쳐진다.
// 별도 트랜잭션의 동시 진행이 본질이라 @Transactional 없이 새 UUID 로 격리하고 finally 에서 정리한다
class ItemSlotMergeConcurrencyIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var wishPersistenceService: WishPersistenceService

    @Autowired private lateinit var itemParsingScheduler: ItemParsingScheduler

    @Autowired private lateinit var stubLinkSnapshotExtractor: StubLinkSnapshotExtractor

    @Autowired private lateinit var userJpaRepository: UserJpaRepository

    @Autowired private lateinit var itemJpaRepository: ItemJpaRepository

    @Autowired private lateinit var itemParseOutboxRepository: ItemParseOutboxRepository

    @Autowired private lateinit var tournamentItemRepository: TournamentItemRepository

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

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
            stubBothExtractionsFinishTogether(finalUrl)

            itemParsingScheduler.awaitTicking(Duration.ofSeconds(10)) { entries.all { jdbcTemplate.parseOutboxStatusOf(it) == ItemParseOutboxStatus.SUCCEEDED } }

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
            stubBothExtractionsFinishTogether(finalUrl)

            itemParsingScheduler.awaitTicking(Duration.ofSeconds(10)) { slots.all { jdbcTemplate.parseOutboxStatusOf(it) == ItemParseOutboxStatus.SUCCEEDED } }

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

    // 두 추출이 함께 끝나야 두 확정이 서로의 커밋 전에 스냅샷을 잡는다
    private fun stubBothExtractionsFinishTogether(finalUrl: String) {
        val bothExtracted = CyclicBarrier(2)
        stubLinkSnapshotExtractor.build = {
            bothExtracted.await(5, TimeUnit.SECONDS)
            ProductSnapshot(
                name = "합칠 상품",
                price = 10_000,
                currency = "KRW",
                imageUrl = "https://img.example.com/m.png",
                finalUrl = ProductLink.parse(finalUrl),
            )
        }
    }
}
