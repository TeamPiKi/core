package com.depromeet.piki.wishlist.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubImageStorage
import com.depromeet.piki.support.StubImageSnapshotExtractor
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import org.awaitility.Awaitility.await
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 이미지 등록 v2(presigned) 흐름 — 발급(POST /images/presigned) → 클라 직접 업로드(stub) → 확정(POST /images/confirm).
// confirm 은 PENDING 을 실제 커밋하고 자동 dispatch(1s)가 파싱을 돌리므로, WishlistRegisterAsyncIntegrationTest 와 같은
// @Transactional 없는(실제 커밋 + Awaitility) 비동기 패턴을 따른다. 격리 userId + cleanup 으로 공유 컨텍스트를 정리한다.
class WishlistImagePresignedIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var stubImageSnapshotExtractor: StubImageSnapshotExtractor

    @Autowired
    private lateinit var stubImageStorage: StubImageStorage

    @Autowired
    private lateinit var itemSnapshotRepository: ItemSnapshotRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    // ---- 발급 (presigned URL) ----

    @Test
    fun `presigned 발급하면 요청한 개수만큼 items_raw key 와 uploadUrl 을 받는다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            val body = objectMapper.writeValueAsString(mapOf("contentTypes" to listOf("image/png", "image/jpeg")))
            val response =
                mockMvc
                    .perform(
                        post("/api/v1/wishlists/images/presigned")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                            .content(body),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.data.uploads.length()").value(2))
                    .andExpect(jsonPath("$.data.uploads[0].contentType").value("image/png"))
                    .andExpect(jsonPath("$.data.uploads[1].contentType").value("image/jpeg"))
                    .andExpect(jsonPath("$.data.uploads[0].uploadUrl").isNotEmpty)
                    .andReturn()
                    .response
                    .getContentAsString(Charsets.UTF_8)

            // imageKey 는 우리가 발급하는 items/raw/{UUID}.{ext} 형식이어야 한다(confirm 의 형식 검증과 짝).
            val uploads = objectMapper.readTree(response).path("data").path("uploads")
            val pngKey = uploads.path(0).path("imageKey").asText()
            val jpgKey = uploads.path(1).path("imageKey").asText()
            assertTrue(Regex("^items/raw/[0-9a-f-]{36}\\.png$").matches(pngKey), "png imageKey 형식: $pngKey")
            assertTrue(Regex("^items/raw/[0-9a-f-]{36}\\.jpg$").matches(jpgKey), "jpg imageKey 형식: $jpgKey")
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `지원하지 않는 content-type 으로 발급하면 400 으로 거부된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            val body = objectMapper.writeValueAsString(mapOf("contentTypes" to listOf("application/pdf")))
            mockMvc
                .perform(
                    post("/api/v1/wishlists/images/presigned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                        .content(body),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.detail").value("지원하지 않는 이미지 형식이에요."))
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `content-type 이 6개면 개수 위반으로 400 이 반환된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            val body = objectMapper.writeValueAsString(mapOf("contentTypes" to List(6) { "image/png" }))
            mockMvc
                .perform(
                    post("/api/v1/wishlists/images/presigned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                        .content(body),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.detail").value("이미지는 1~5장만 올릴 수 있어요."))
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `게스트가 발급하면 회원 전용 403 으로 거부된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertGuest(userId)
        try {
            val body = objectMapper.writeValueAsString(mapOf("contentTypes" to listOf("image/png")))
            mockMvc
                .perform(
                    post("/api/v1/wishlists/images/presigned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${guestToken(userId)}")
                        .content(body),
                ).andExpect(status().isForbidden)
                .andExpect(jsonPath("$.detail").value("위시리스트는 회원만 이용할 수 있어요."))
        } finally {
            cleanup(userId)
        }
    }

    // ---- 확정 (confirm) ----

    @Test
    fun `발급받은 key 로 confirm 하면 PENDING 위시가 201 로 생성된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            // 자동 dispatch(1s)가 PENDING 을 집어 워커를 돌리므로, 깨끗한 추출 결과를 세팅해 둔다.
            stubImageSnapshotExtractor.build = { StubImageSnapshotExtractor.defaultSnapshot() }
            val keys = presignAndGetKeys(mockMvc, userId, listOf("image/png", "image/jpeg"))
            val body = objectMapper.writeValueAsString(mapOf("imageKeys" to keys))

            mockMvc
                .perform(
                    post("/api/v1/wishlists/images/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                        .content(body),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.data.length()").value(2))
                // 등록 직후 응답은 PENDING 이어야 한다(이미지도 link 처럼 outbox 에 적재).
                .andExpect(jsonPath("$.data[0].item.status").value("PENDING"))
                // 이미지 등록은 원본 URL 이 없어 sourceUrl 이 null 이다.
                .andExpect(jsonPath("$.data[0].item.sourceUrl").value(nullValue()))

            val wishCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wishes WHERE user_id = ?", Int::class.java, uuidToBytes(userId))
            assertEquals(2, wishCount)
            awaitDispatchSettled(userId)
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `같은 key 로 confirm 을 두 번 하면 두 번째는 빈 목록 201 이고 위시가 중복 생기지 않는다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubImageSnapshotExtractor.build = { StubImageSnapshotExtractor.defaultSnapshot() }
            val keys = presignAndGetKeys(mockMvc, userId, listOf("image/png", "image/jpeg"))
            val body = objectMapper.writeValueAsString(mapOf("imageKeys" to keys))

            // 1차 confirm — pending 을 claim(삭제)하며 2개 등록.
            mockMvc
                .perform(
                    post("/api/v1/wishlists/images/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                        .content(body),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.data.length()").value(2))

            // 2차 confirm(같은 key) — raw 는 S3 에 남아 형식·존재 검증은 통과하지만 pending 이 이미 claim 돼 등록할 게 없다.
            // 멱등 no-op 으로 빈 목록 201 이고, 위시는 중복 생기지 않는다.
            mockMvc
                .perform(
                    post("/api/v1/wishlists/images/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                        .content(body),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.data.length()").value(0))

            val wishCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wishes WHERE user_id = ?", Int::class.java, uuidToBytes(userId))
            assertEquals(2, wishCount)
            awaitDispatchSettled(userId)
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `업로드하지 않은 key 로 confirm 하면 400 으로 거부되고 위시가 생기지 않는다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            // S3 에 실제로 올라오지 않은 상황 재현 — HEAD 존재확인이 false 를 돌려준다.
            stubImageStorage.existsBehavior = { false }
            val keys = presignAndGetKeys(mockMvc, userId, listOf("image/png"))
            val body = objectMapper.writeValueAsString(mapOf("imageKeys" to keys))

            mockMvc
                .perform(
                    post("/api/v1/wishlists/images/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                        .content(body),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.detail").value("아직 업로드되지 않은 이미지예요. 업로드를 마친 뒤 다시 시도해 주세요."))

            val wishCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wishes WHERE user_id = ?", Int::class.java, uuidToBytes(userId))
            assertEquals(0, wishCount)
        } finally {
            stubImageStorage.existsBehavior = stubImageStorage.defaultExistsBehavior
            cleanup(userId)
        }
    }

    @Test
    fun `발급 형식이 아닌 key 로 confirm 하면 400 으로 거부된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            val body = objectMapper.writeValueAsString(mapOf("imageKeys" to listOf("items/raw/not-a-uuid.png")))
            mockMvc
                .perform(
                    post("/api/v1/wishlists/images/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                        .content(body),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.detail").value("올바르지 않은 이미지 업로드 정보예요. 업로드를 다시 시도해 주세요."))
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `confirm 후 자동 dispatch 로 파싱이 성공하면 item 이 READY 로 전이한다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubImageSnapshotExtractor.build = {
                ProductSnapshot(link = null, name = "나이키 에어포스", currentPrice = 99_000, currency = "KRW", imageUrl = "https://img.example.com/af.png")
            }
            val keys = presignAndGetKeys(mockMvc, userId, listOf("image/png"))
            val body = objectMapper.writeValueAsString(mapOf("imageKeys" to keys))
            val response =
                mockMvc
                    .perform(
                        post("/api/v1/wishlists/images/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                            .content(body),
                    ).andExpect(status().isCreated)
                    .andReturn()
                    .response
                    .getContentAsString(Charsets.UTF_8)
            val itemId =
                objectMapper
                    .readTree(response)
                    .path("data")
                    .path(0)
                    .path("item")
                    .path("id")
                    .asLong()

            await().atMost(Duration.ofSeconds(5)).until {
                itemSnapshotRepository.findLatestByItemId(itemId)?.status == ItemStatus.READY
            }
            val snapshot = itemSnapshotRepository.findLatestByItemId(itemId) ?: error("item $itemId 의 snapshot 이 없다")
            assertEquals("나이키 에어포스", snapshot.name)
        } finally {
            cleanup(userId)
        }
    }

    // ---- 헬퍼 ----

    private fun presignAndGetKeys(
        mockMvc: MockMvc,
        userId: UUID,
        contentTypes: List<String>,
    ): List<String> {
        val body = objectMapper.writeValueAsString(mapOf("contentTypes" to contentTypes))
        val response =
            mockMvc
                .perform(
                    post("/api/v1/wishlists/images/presigned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                        .content(body),
                ).andExpect(status().isOk)
                .andReturn()
                .response
                .getContentAsString(Charsets.UTF_8)
        val uploads = objectMapper.readTree(response).path("data").path("uploads")
        return (0 until uploads.size()).map { uploads.path(it).path("imageKey").asText() }
    }

    private fun buildMockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun insertMember(userId: UUID) = insertUser(userId, "MEMBER")

    private fun insertGuest(userId: UUID) = insertUser(userId, "GUEST")

    private fun insertUser(
        userId: UUID,
        identityType: String,
    ) {
        jdbcTemplate.update(
            "INSERT INTO users (id, nickname, identity_type, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
            uuidToBytes(userId),
            userId.toString().take(10),
            identityType,
        )
    }

    private fun memberToken(userId: UUID): String = jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)

    private fun guestToken(userId: UUID): String = jwtProvider.generateAccessToken(userId, IdentityType.GUEST)

    private fun cleanup(userId: UUID) {
        val itemIds =
            jdbcTemplate.queryForList(
                "SELECT s.item_id FROM wishes w JOIN item_snapshots s ON s.id = w.snapshot_id WHERE w.user_id = ?",
                Long::class.java,
                uuidToBytes(userId),
            )
        jdbcTemplate.update("DELETE FROM wishes WHERE user_id = ?", uuidToBytes(userId))
        itemIds.takeIf { it.isNotEmpty() }?.let {
            jdbcTemplate.update("DELETE FROM item_snapshots WHERE item_id IN (${it.joinToString(",")})")
            jdbcTemplate.update("DELETE FROM items WHERE id IN (${it.joinToString(",")})")
        }
        // presign 만 하고 confirm 을 안 한(또는 confirm 이 400 인) 케이스의 pending_uploads 가 남지 않게 함께 지운다(FK 는 없음, orphan 위생).
        jdbcTemplate.update("DELETE FROM pending_uploads WHERE user_id = ?", uuidToBytes(userId))
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(userId))
    }

    // confirm 이 만든 PENDING 을 자동 dispatch(1s)가 파싱 중일 수 있어, 삭제-vs-워커 UPDATE 레이스를 피하려면
    // 후속 처리가 terminal(READY/FAILED)로 끝난 뒤 cleanup 한다.
    private fun awaitDispatchSettled(userId: UUID) {
        await().atMost(Duration.ofSeconds(5)).until {
            val inFlight =
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM item_snapshots s JOIN wishes w ON w.snapshot_id = s.id " +
                        "WHERE w.user_id = ? AND s.status IN ('PENDING', 'PROCESSING')",
                    Int::class.java,
                    uuidToBytes(userId),
                ) ?: 0
            inFlight == 0
        }
    }
}
