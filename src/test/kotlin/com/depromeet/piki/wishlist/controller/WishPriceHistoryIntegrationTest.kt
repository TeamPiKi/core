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

// 상세 조회(GET /wishlists/{wishId})가 함께 내려주는 가격 이력의 계약을 고정한다. 동기 조회라 @Transactional 자동 롤백으로 격리하고,
// 한 item 에 버전을 직접 쌓아 "갱신·새로고침·수기 수정이 누적된 상태"를 시딩한다.
//
// 고정하는 계약: 기계(SERVER/SERVER_LLM) READY 만 최신순으로, 수기(MANUAL)·출처 미상(null)·미완성(PENDING/PROCESSING/FAILED)·
// soft-delete 제외, 상한 50건. 그리고 **item 과 priceHistory 가 별개의 축**이라는 것 — 표시값이 이력에 없을 수 있고 그것이 정상이다.
@Transactional
class WishPriceHistoryIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var itemRepository: ItemRepository

    @Autowired
    private lateinit var itemSnapshotRepository: ItemSnapshotRepository

    @Autowired
    private lateinit var wishRepository: WishRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Test
    fun `상세 조회하면 표시값과 가격 이력이 함께 내려가고 이력은 최신순이다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val itemId = saveItem("https://shop.example.com/products/history")
        saveMachineReady(itemId, "옛 상품", 119_000, LocalDateTime.of(2026, 5, 1, 10, 0))
        saveMachineReady(itemId, "중간 상품", 99_000, LocalDateTime.of(2026, 5, 15, 10, 0))
        val active = saveMachineReady(itemId, "현재 상품", 109_000, LocalDateTime.of(2026, 6, 1, 10, 0))
        val wishId = saveWish(userId, active)

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.wish.id").value(wishId))
            .andExpect(jsonPath("$.data.item.id").value(itemId))
            .andExpect(jsonPath("$.data.item.status").value("READY"))
            .andExpect(jsonPath("$.data.item.source").value("SERVER"))
            .andExpect(jsonPath("$.data.item.name").value("현재 상품"))
            .andExpect(jsonPath("$.data.item.price").value(109_000))
            .andExpect(jsonPath("$.data.item.sourceUrl").value("https://shop.example.com/products/history"))
            // 백오피스(source_platforms) 미등록 도메인 — host 에서 유도한 임시 표시명(등록 가능 도메인의 첫 라벨)이 나간다.
            .andExpect(jsonPath("$.data.item.sourcePlatform").value("example"))
            .andExpect(jsonPath("$.data.priceHistory.length()").value(3))
            // 최신순(id desc). 항목은 가격과 추출시각 둘뿐이다.
            .andExpect(jsonPath("$.data.priceHistory[0].price").value(109_000))
            // 시각은 UTC wall-clock 으로 저장하고 응답에서 KST(+09:00)로 변환한다(JacksonConfig).
            // KST 는 DST 가 없는 고정 오프셋이라 이 단언은 실행 환경 타임존과 무관하게 결정적이다.
            .andExpect(jsonPath("$.data.priceHistory[0].extractedAt").value("2026-06-01T19:00:00+09:00"))
            .andExpect(jsonPath("$.data.priceHistory[1].price").value(99_000))
            .andExpect(jsonPath("$.data.priceHistory[2].price").value(119_000))
            // 이력이 별도 API 로 갈리지 않게 한 결과 — 상세 응답 하나로 끝난다.
            .andExpect(jsonPath("$.data.priceHistory[0].snapshotId").doesNotExist())
            .andExpect(jsonPath("$.data.priceHistory[0].isActive").doesNotExist())
            .andExpect(jsonPath("$.data.activeSnapshotId").doesNotExist())
    }

    @Test
    fun `수기와 출처 미상 버전은 가격 이력에서 빠진다 - 남의 수기도 내 수기도`() {
        // 이 필터가 이 작업의 핵심이다. 수기값은 시세 관측치가 아니고, 스냅샷을 지우는 경로가 없어
        // 한 번 잘못 입력하면 추이에 영구히 남는다. 출처 미상(도입 전 행)은 기계 여부를 소급 판정할 수 없어 함께 뺀다.
        val mockMvc = buildMockMvc()
        val me = UUID.randomUUID()
        val other = UUID.randomUUID()
        insertMember(me)
        val itemId = saveItem("https://shop.example.com/products/source-labels")
        saveVersion(itemId, "출처 미상", 90_000, source = null, editedBy = null)
        val machine = saveVersion(itemId, "기계 추출", 95_000, source = ItemSnapshotSource.SERVER, editedBy = null)
        saveVersion(itemId, "타인 수기", 80_000, source = ItemSnapshotSource.MANUAL, editedBy = other)
        saveVersion(itemId, "LLM 추출", 97_000, source = ItemSnapshotSource.SERVER_LLM, editedBy = null)
        saveVersion(itemId, "내 수기", 1_190_000, source = ItemSnapshotSource.MANUAL, editedBy = me)
        val wishId = saveWish(me, machine)

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(me)}"),
            ).andExpect(status().isOk)
            // 다섯 버전 중 기계 둘(SERVER·SERVER_LLM)만 남는다. 오타로 넣은 119만원짜리 내 수기도 빠진다.
            .andExpect(jsonPath("$.data.priceHistory.length()").value(2))
            .andExpect(jsonPath("$.data.priceHistory[0].price").value(97_000))
            .andExpect(jsonPath("$.data.priceHistory[1].price").value(95_000))
            .andExpect(jsonPath("$.data.item.id").value(itemId))
            // 표시값은 파생 규칙(#857)대로 마지막 기계 READY 인 LLM 버전이다 — 포인터(machine)보다 뒤에 쌓였으므로.
            .andExpect(jsonPath("$.data.item.price").value(97_000))
            .andExpect(jsonPath("$.data.item.source").value("SERVER_LLM"))
    }

    @Test
    fun `표시값이 수기면 그 값은 가격 이력에 없다 - 상단 카드와 그래프 끝점이 달라도 정상이다`() {
        // item 과 priceHistory 는 별개의 축이라는 계약. 둘을 조인해 맞춰보려는 클라이언트가 있으면 여기서 깨진다.
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val itemId = saveItem("https://shop.example.com/products/manual-display")
        saveMachineReady(itemId, "기계 값", 109_000, LocalDateTime.of(2026, 6, 1, 10, 0))
        // 기계 READY 보다 뒤에 쌓인 내 수기 — 표시값은 이 값이 되지만(수기 존중) 이력에는 안 들어간다.
        val myEdit = saveVersion(itemId, "내가 고친 값", 99_000, source = ItemSnapshotSource.MANUAL, editedBy = userId)
        val wishId = saveWish(userId, myEdit)

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.item.price").value(99_000))
            .andExpect(jsonPath("$.data.item.source").value("MANUAL"))
            // 이력에는 기계값만 — 표시값 99,000원은 여기 없다.
            .andExpect(jsonPath("$.data.priceHistory.length()").value(1))
            .andExpect(jsonPath("$.data.priceHistory[0].price").value(109_000))
    }

    @Test
    fun `가격 이력에는 READY 버전만 포함되고 PENDING·PROCESSING·FAILED 는 제외된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val itemId = saveItem("https://shop.example.com/products/mixed")
        val ready = saveMachineReady(itemId, "완성 버전", 50_000, LocalDateTime.now())
        // 같은 item 에 가격 없는 버전들을 섞어 둔다 — 이력에서 빠져야 한다.
        itemSnapshotRepository.save(ItemSnapshot.pending(itemId))
        itemSnapshotRepository.save(ItemSnapshot.pending(itemId).apply { markProcessing() })
        itemSnapshotRepository.save(ItemSnapshot(itemId = itemId, status = ItemStatus.FAILED))
        val wishId = saveWish(userId, ready)

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.priceHistory.length()").value(1))
            .andExpect(jsonPath("$.data.priceHistory[0].price").value(50_000))
    }

    @Test
    fun `soft-delete 된 READY snapshot 은 가격 이력에서 제외된다`() {
        // production 쿼리의 deletedAt IS NULL 필터 회귀 방지 — status·source 필터와 별개로 삭제된 행이 새지 않는지 고정한다.
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val itemId = saveItem("https://shop.example.com/products/soft-deleted")
        val live = saveMachineReady(itemId, "살아있는 버전", 30_000, LocalDateTime.now())
        itemSnapshotRepository.save(
            ItemSnapshot(
                itemId = itemId,
                name = "삭제된 버전",
                price = 99_000,
                currency = "KRW",
                imageUrl = "https://cdn.example.com/p/deleted.jpg",
                status = ItemStatus.READY,
                extractedAt = LocalDateTime.now(),
                source = ItemSnapshotSource.SERVER,
            ).apply { deletedAt = LocalDateTime.now() },
        )
        val wishId = saveWish(userId, live)

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.priceHistory.length()").value(1))
            .andExpect(jsonPath("$.data.priceHistory[0].price").value(30_000))
    }

    @Test
    fun `아직 추출 성공 이력이 없으면 빈 배열이고 status 로 대기와 실패를 가른다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val itemId = saveItem("https://shop.example.com/products/pending-only")
        val pending = itemSnapshotRepository.save(ItemSnapshot.pending(itemId)).getId()
        val wishId = saveWish(userId, pending)

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isOk)
            // 값이 비어 있을 때 "기다린다"와 "그만 기다린다"를 가르는 유일한 수단이 status 다.
            .andExpect(jsonPath("$.data.item.status").value("PENDING"))
            .andExpect(jsonPath("$.data.item.price").doesNotExist())
            .andExpect(jsonPath("$.data.priceHistory.length()").value(0))
    }

    @Test
    fun `가격 이력은 최신 50건까지만 내려간다`() {
        // item 을 여러 사용자가 공유해 새로고침이 누적되므로 상한이 없으면 응답이 시간에 비례해 자란다.
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val itemId = saveItem("https://shop.example.com/products/many-versions")
        // 55건을 1,000원씩 올려가며 쌓는다 — 최신 50건이면 가장 오래된 5건(1,000~5,000원)이 잘린다.
        var last = 0L
        repeat(55) { i ->
            last = saveMachineReady(itemId, "버전 $i", (i + 1) * 1_000, LocalDateTime.now())
        }
        val wishId = saveWish(userId, last)

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.priceHistory.length()").value(50))
            // 최신순이라 맨 앞이 마지막에 쌓은 55,000원, 맨 뒤가 6,000원(1~5,000원은 상한에 잘림)
            .andExpect(jsonPath("$.data.priceHistory[0].price").value(55_000))
            .andExpect(jsonPath("$.data.priceHistory[49].price").value(6_000))
    }

    @Test
    fun `옛 가격 히스토리 엔드포인트는 더 이상 존재하지 않는다`() {
        // 상세 조회로 흡수됐다. 남아 있으면 표시값 파생을 타지 않는 옛 계약이 함께 사는 것이라 회귀로 고정한다.
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val itemId = saveItem("https://shop.example.com/products/gone")
        val snapshot = saveMachineReady(itemId, "상품", 10_000, LocalDateTime.now())
        val wishId = saveWish(userId, snapshot)

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId/history")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `탈퇴한 회원의 살아있는 access token 으로 상세 조회하면 409 가 반환된다`() {
        // 소유권 검증(verifyOwnedBy)만으로는 탈퇴 여부를 못 본다 — 본인 위시이므로 그대로 통과해버린다.
        // 회원 가드가 활성 조회로 끊어야 탈퇴 회원이 자기 가격 이력을 계속 읽는 것을 막는다 (#691).
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val itemId = saveItem("https://shop.example.com/products/withdrawn")
        val snapshot = saveMachineReady(itemId, "내 상품", 10_000, LocalDateTime.now())
        val wishId = saveWish(userId, snapshot)
        jdbcTemplate.update("UPDATE users SET deleted_at = NOW(6) WHERE id = ?", uuidToBytes(userId))

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("USER-003"))
            .andExpect(jsonPath("$.detail").value("탈퇴한 계정이에요."))
    }

    private fun saveItem(url: String): Long = itemRepository.save(Item(ProductLink.parse(url))).getId()

    // 출처를 명시해 쌓는 READY 시딩. 순서(최신순)는 id 단조증가에 맡긴다.
    private fun saveVersion(
        itemId: Long,
        name: String,
        price: Int,
        source: ItemSnapshotSource?,
        editedBy: UUID?,
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

    // 기계 추출 READY — 가격 이력에 실제로 실리는 유일한 종류다.
    private fun saveMachineReady(
        itemId: Long,
        name: String,
        price: Int,
        extractedAt: LocalDateTime,
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
                    extractedAt = extractedAt,
                    source = ItemSnapshotSource.SERVER,
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
