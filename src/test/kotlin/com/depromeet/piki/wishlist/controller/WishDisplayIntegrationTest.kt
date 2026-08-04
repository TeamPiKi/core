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

// 카드 표시값 파생(#857)의 위시 계약 검증 — "최신 기계 READY 는 절대 지지 않는다". 포인터가 옛 버전·수기를
// 가리켜도 화면 값은 파생 규칙(기계 우선·수기는 자기 맥락 한정·기계 없으면 포인터 fallback)을 따른다.
// 시딩은 저장소 직접 적재(파생은 조회 계층 순수 로직이라 등록·파싱 흐름과 독립적으로 검증 가능) — @Transactional 롤백 격리.
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
        val v1 = saveVersion(itemId, "옛 기계값", 100_000, ItemSnapshotSource.SERVER)
        val wishId = saveWish(userA, v1)
        // 다른 참조(다른 위시·갱신)가 만든 새 기계 버전 — A 의 포인터는 여전히 v1 이다.
        saveVersion(itemId, "새 기계값", 90_000, ItemSnapshotSource.SERVER)

        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userA)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].item.name").value("새 기계값"))
            .andExpect(jsonPath("$.data[0].item.price").value(90_000))
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
        val machine = saveVersion(itemId, "기계값", 100_000, ItemSnapshotSource.SERVER)
        val manual = saveVersion(itemId, "A의 수기값", 80_000, ItemSnapshotSource.MANUAL, editedBy = userA)
        saveWish(userA, manual)
        saveWish(userB, machine)

        // 수기가 놓인 맥락(A 의 위시)은 수기값 — 아직 그보다 새로운 기계가 없으므로 존중된다.
        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userA)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].item.name").value("A의 수기값"))
            .andExpect(jsonPath("$.data[0].item.price").value(80_000))
        // 관련 없는 맥락(B 의 위시)은 타인의 수기와 무관하게 마지막 기계값.
        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userB)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].item.name").value("기계값"))
            .andExpect(jsonPath("$.data[0].item.price").value(100_000))
    }

    @Test
    fun `수기 뒤에 새 기계 READY 가 생기면 수기를 놓은 위시도 기계값으로 돌아간다 - 기계는 절대 지지 않는다`() {
        val mockMvc = buildMockMvc()
        val userA = UUID.randomUUID()
        insertMember(userA)
        val itemId = saveItem("https://shop.example.com/products/display-3")
        saveVersion(itemId, "기계값", 100_000, ItemSnapshotSource.SERVER)
        val manual = saveVersion(itemId, "A의 수기값", 80_000, ItemSnapshotSource.MANUAL, editedBy = userA)
        saveWish(userA, manual)
        // 수기보다 새로운 기계 READY — 수기 존중은 여기서 끝난다.
        saveVersion(itemId, "더 새 기계값", 95_000, ItemSnapshotSource.SERVER)

        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userA)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].item.name").value("더 새 기계값"))
            .andExpect(jsonPath("$.data[0].item.price").value(95_000))
    }

    @Test
    fun `갱신 중 포인터는 기계 READY 가 있어도 진행 중 상태를 유지한다 - 자기가 시작한 갱신의 UX 신호`() {
        val mockMvc = buildMockMvc()
        val userA = UUID.randomUUID()
        insertMember(userA)
        val itemId = saveItem("https://shop.example.com/products/display-5")
        saveVersion(itemId, "기계값", 100_000, ItemSnapshotSource.SERVER)
        // 새로고침이 만든 진행 중 버전에 포인터가 걸린 상태 — 완성 값(기계 READY)이 있어도 진행 중 표시가 유지돼야
        // 갱신 흐름의 프로세싱 UI 가 살아 있다(값이 없어 이김/짐의 대상이 아니다).
        val inProgress = itemSnapshotRepository.save(ItemSnapshot.pending(itemId))
        saveWish(userA, inProgress.getId())

        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userA)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].item.status").value("PENDING"))
            .andExpect(jsonPath("$.data[0].item.name").doesNotExist())
    }

    @Test
    fun `기계 READY 가 없는 상품은 포인터 버전을 그대로 보여준다 - 수기 복구·도입 전 데이터 fallback`() {
        val mockMvc = buildMockMvc()
        val userA = UUID.randomUUID()
        insertMember(userA)
        val itemId = saveItem("https://shop.example.com/products/display-4")
        // 추출 실패를 수기로 복구해 수기값이 유일한 값인 상품.
        val manual = saveVersion(itemId, "수기 복구값", 70_000, ItemSnapshotSource.MANUAL, editedBy = userA)
        saveWish(userA, manual)

        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userA)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].item.name").value("수기 복구값"))
            .andExpect(jsonPath("$.data[0].item.price").value(70_000))
    }

    private fun saveItem(url: String): Long = itemRepository.save(Item(link = ProductLink.parse(url))).getId()

    private fun saveVersion(
        itemId: Long,
        name: String,
        price: Int,
        source: ItemSnapshotSource?,
        editedBy: UUID? = null,
    ): Long =
        itemSnapshotRepository
            .save(
                ItemSnapshot(
                    itemId = itemId,
                    name = name,
                    price = price,
                    currency = "KRW",
                    imageUrl = "https://cdn.example.com/p/$price.jpg",
                    status = ItemStatus.READY,
                    extractedAt = LocalDateTime.now(),
                    source = source,
                    editedBy = editedBy,
                ),
            ).getId()

    private fun saveWish(
        userId: UUID,
        snapshotId: Long,
    ): Long = wishRepository.save(Wish(userId = userId, snapshotId = snapshotId)).getId()

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
