package com.depromeet.piki.wishlist.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemSnapshotSource
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.repository.WishRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import java.time.LocalDateTime
import java.util.UUID

// 카드 표시값 파생(#857·#1051)의 위시 계약 검증 — 화면값은 포인터가 아니라 "내 맥락의 값 vs 공유 기계 READY" 로 계산한다.
// 분기 망라는 ItemVersionsTest(단위)가 맡고, 여기는 목록·단건 API 가 그 규칙을 실제로 타는지와 포인터가 판정에
// 끼어들지 않는지를 HTTP 경계에서 고정한다. 시딩은 저장소 직접 적재 — @Transactional 롤백 격리.
@Transactional
class WishDisplayIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var itemRepository: ItemRepository

    @Autowired private lateinit var itemSnapshotRepository: ItemSnapshotRepository

    @Autowired private lateinit var wishRepository: WishRepository

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Test
    fun `포인터가 옛 버전이어도 위시 목록·단건은 그 상품의 마지막 기계 READY 값을 보여준다`() {
        val mockMvc = buildMockMvc()
        val userA = UUID.randomUUID()
        insertMember(userA)
        val itemId = saveItem("https://shop.example.com/products/display-1")
        val v1 = saveVersion(itemId, "옛 기계값", 100_000, ItemSnapshotSource.SERVER, by = userA)
        val wishId = saveWish(userA, itemId, v1)
        // 다른 참조(다른 위시·갱신)가 만든 새 기계 버전 — A 의 포인터는 여전히 v1 이다.
        saveVersion(itemId, "새 기계값", 90_000, ItemSnapshotSource.SERVER, by = UUID.randomUUID())

        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userA)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].item.name").value("새 기계값"))
            .andExpect(jsonPath("$.data[0].item.price").value(90_000))
            .andExpect(jsonPath("$.data[0].item.sourcePlatform").value("example"))
        mockMvc
            .perform(get("/api/v1/wishlists/$wishId").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userA)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.item.name").value("새 기계값"))
            .andExpect(jsonPath("$.data.item.price").value(90_000))
    }

    @Test
    fun `수기가 최신이면 수기를 놓은 위시만 수기값을 보고 같은 상품의 다른 위시는 기계값을 본다`() {
        val mockMvc = buildMockMvc()
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        insertMember(userA)
        insertMember(userB)
        val itemId = saveItem("https://shop.example.com/products/display-2")
        val machine = saveVersion(itemId, "기계값", 100_000, ItemSnapshotSource.SERVER, by = userB)
        val manual = saveVersion(itemId, "A의 수기값", 80_000, ItemSnapshotSource.MANUAL, by = userA)
        saveWish(userA, itemId, manual)
        saveWish(userB, itemId, machine)

        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userA)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].item.name").value("A의 수기값"))
            .andExpect(jsonPath("$.data[0].item.price").value(80_000))
        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userB)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].item.name").value("기계값"))
            .andExpect(jsonPath("$.data[0].item.price").value(100_000))
    }

    @Test
    fun `수기 뒤에 새 기계 READY 가 생기면 수기를 놓은 위시도 기계값으로 돌아간다`() {
        val mockMvc = buildMockMvc()
        val userA = UUID.randomUUID()
        insertMember(userA)
        val itemId = saveItem("https://shop.example.com/products/display-3")
        saveVersion(itemId, "기계값", 100_000, ItemSnapshotSource.SERVER, by = userA)
        val manual = saveVersion(itemId, "A의 수기값", 80_000, ItemSnapshotSource.MANUAL, by = userA)
        saveWish(userA, itemId, manual)
        // 수기보다 새로운 기계 READY(누가 새로고침했든) — 수기 존중은 여기서 끝난다.
        saveVersion(itemId, "더 새 기계값", 95_000, ItemSnapshotSource.SERVER, by = UUID.randomUUID())

        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userA)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].item.name").value("더 새 기계값"))
            .andExpect(jsonPath("$.data[0].item.price").value(95_000))
    }

    @Test
    fun `내가 시작한 갱신은 기계 READY 가 있어도 진행 중 상태를 보인다`() {
        val mockMvc = buildMockMvc()
        val userA = UUID.randomUUID()
        insertMember(userA)
        val itemId = saveItem("https://shop.example.com/products/display-5")
        saveVersion(itemId, "기계값", 100_000, ItemSnapshotSource.SERVER, by = userA)
        val inProgress = itemSnapshotRepository.save(ItemSnapshot.pending(itemId, requestedBy = userA))
        saveWish(userA, itemId, inProgress.getId())

        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userA)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].item.status").value("PENDING"))
            .andExpect(jsonPath("$.data[0].item.name").doesNotExist())
    }

    @Test
    fun `남이 시작한 갱신은 내 카드에 보이지 않는다 - 내 카드는 완성 값을 그대로 본다`() {
        // 불변식(#1051): 남의 새로고침 진행 중·남의 미완은 내 카드에 새어 들어오지 않는다. 포인터가 아니라 행의 만든 사람으로 가른다.
        val mockMvc = buildMockMvc()
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        insertMember(userA)
        val itemId = saveItem("https://shop.example.com/products/display-6")
        val machine = saveVersion(itemId, "기계값", 100_000, ItemSnapshotSource.SERVER, by = userA)
        saveWish(userA, itemId, machine)
        itemSnapshotRepository.save(ItemSnapshot.pending(itemId, requestedBy = userB))
        saveVersion(itemId, "B의 미완", null, ItemSnapshotSource.SERVER, by = userB, status = ItemStatus.INCOMPLETE)

        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userA)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].item.status").value("READY"))
            .andExpect(jsonPath("$.data[0].item.name").value("기계값"))
    }

    @Test
    fun `새로고침이 실패해도 내 수기값은 그대로다 - 포인터가 FAILED 로 옮겨 가도 카드는 새로고침 전과 같다`() {
        val mockMvc = buildMockMvc()
        val userA = UUID.randomUUID()
        insertMember(userA)
        val itemId = saveItem("https://shop.example.com/products/display-7")
        // 추출 실패를 수기로 복구해 수기값이 유일한 값인 상품 — 새로고침이 실패하면 옛 규칙에선 빈 카드가 됐다.
        saveVersion(itemId, "수기 복구값", 70_000, ItemSnapshotSource.MANUAL, by = userA)
        val failed =
            itemSnapshotRepository.save(
                ItemSnapshot.pending(itemId, requestedBy = userA).apply {
                    markProcessing()
                    markFailed()
                },
            )
        saveWish(userA, itemId, failed.getId())

        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userA)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].item.name").value("수기 복구값"))
            .andExpect(jsonPath("$.data[0].item.price").value(70_000))
    }

    @Test
    fun `내 새로고침이 INCOMPLETE 로 끝나면 옛 READY 대신 일부만 빈 상태를 보인다`() {
        val mockMvc = buildMockMvc()
        val userA = UUID.randomUUID()
        insertMember(userA)
        val itemId = saveItem("https://shop.example.com/products/display-8")
        saveVersion(itemId, "기계값", 100_000, ItemSnapshotSource.SERVER, by = userA)
        val incomplete =
            saveVersion(itemId, "일부만", null, ItemSnapshotSource.SERVER, by = userA, status = ItemStatus.INCOMPLETE)
        saveWish(userA, itemId, incomplete)

        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userA)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].item.status").value("INCOMPLETE"))
            .andExpect(jsonPath("$.data[0].item.name").value("일부만"))
            .andExpect(jsonPath("$.data[0].item.price").doesNotExist())
    }

    private fun saveItem(url: String): Long = itemRepository.save(Item(link = ProductLink.parse(url))).getId()

    private fun saveVersion(
        itemId: Long,
        name: String,
        price: Int?,
        source: ItemSnapshotSource?,
        by: UUID?,
        status: ItemStatus = ItemStatus.READY,
    ): Long =
        itemSnapshotRepository
            .save(
                ItemSnapshot(
                    itemId = itemId,
                    name = name,
                    price = price,
                    currency = "KRW",
                    imageUrl = price?.let { "https://cdn.example.com/p/$it.jpg" },
                    status = status,
                    extractedAt = LocalDateTime.now(),
                    source = source,
                        createdBy = by,
                ),
            ).getId()

    private fun saveWish(
        userId: UUID,
        itemId: Long,
        snapshotId: Long,
    ): Long = wishRepository.save(Wish(userId = userId, waitingSnapshotId = snapshotId, itemId = itemId)).getId()

    private fun buildMockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun insertMember(userId: UUID) {
        jdbcTemplate.update(
            "INSERT INTO users (id, nickname, identity_type, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
            uuidToBytes(userId),
            userId.toString().take(10),
            "MEMBER",
        )
    }

    private fun memberToken(userId: UUID): String = jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)
}
