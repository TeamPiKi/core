package com.depromeet.piki.tournament.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubImageStorage
import com.depromeet.piki.support.StubImageSnapshotExtractor
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import org.awaitility.Awaitility.await
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

// 토너먼트 이미지 등록 v2(presigned) — 발급(POST .../images/presigned) → 클라 직접 업로드(stub) → 확정(POST .../images/confirm).
// 위시와 같은 공통 ImagePresignService 를 쓰되, 권한이 requireMember 가 아니라 verifyCanAddItems(참여자·PENDING·비복제)다.
// confirm 은 PENDING 을 실제 커밋하고 자동 dispatch(1s)가 파싱을 돌리므로 @Transactional 없이 실제 커밋 + finally 정리한다.
class TournamentItemImagePresignedIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var stubImageStorage: StubImageStorage

    @Autowired
    private lateinit var stubImageSnapshotExtractor: StubImageSnapshotExtractor

    @Test
    fun `presigned 발급하면 요청한 개수만큼 uploadUrl 을 받는다`() {
        val mockMvc = buildMockMvc()
        val ownerId = UUID.randomUUID()
        insertGuest(ownerId)
        var tournamentId = 0L
        try {
            tournamentId = createTournament(mockMvc, ownerId)
            val body = objectMapper.writeValueAsString(mapOf("contentTypes" to listOf("image/png", "image/jpeg")))
            mockMvc
                .perform(
                    post("/api/v1/tournaments/$tournamentId/items/images/presigned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(ownerId)}")
                        .content(body),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.data.uploads.length()").value(2))
                .andExpect(jsonPath("$.data.uploads[0].contentType").value("image/png"))
                .andExpect(jsonPath("$.data.uploads[0].uploadUrl").isNotEmpty)
        } finally {
            cleanup(ownerId, tournamentId)
        }
    }

    @Test
    fun `참여자가 아니면 발급이 403 으로 거부된다`() {
        val mockMvc = buildMockMvc()
        val ownerId = UUID.randomUUID()
        insertGuest(ownerId)
        val outsiderId = UUID.randomUUID()
        insertGuest(outsiderId)
        var tournamentId = 0L
        try {
            tournamentId = createTournament(mockMvc, ownerId)
            val body = objectMapper.writeValueAsString(mapOf("contentTypes" to listOf("image/png")))
            mockMvc
                .perform(
                    post("/api/v1/tournaments/$tournamentId/items/images/presigned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(outsiderId)}")
                        .content(body),
                ).andExpect(status().isForbidden)
                .andExpect(jsonPath("$.detail").value("이 토너먼트에 접근할 수 없어요."))
        } finally {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(outsiderId))
            cleanup(ownerId, tournamentId)
        }
    }

    @Test
    fun `발급받은 key 로 confirm 하면 200 으로 아이템이 추가된다`() {
        val mockMvc = buildMockMvc()
        val ownerId = UUID.randomUUID()
        insertGuest(ownerId)
        var tournamentId = 0L
        try {
            // 자동 dispatch(1s)가 PENDING 을 집어 워커를 돌리므로 깨끗한 추출 결과를 세팅해 둔다.
            stubImageSnapshotExtractor.build = { StubImageSnapshotExtractor.defaultSnapshot() }
            tournamentId = createTournament(mockMvc, ownerId)
            val keys = presignAndGetKeys(mockMvc, ownerId, tournamentId, listOf("image/png", "image/jpeg"))
            val body = objectMapper.writeValueAsString(mapOf("imageKeys" to keys))

            mockMvc
                .perform(
                    post("/api/v1/tournaments/$tournamentId/items/images/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(ownerId)}")
                        .content(body),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.data.tournamentItemIds.length()").value(2))

            val count =
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tournament_items WHERE tournament_id = ?",
                    Int::class.java,
                    tournamentId,
                )
            assertEquals(2, count)
            awaitDispatchSettled(tournamentId)
        } finally {
            cleanup(ownerId, tournamentId)
        }
    }

    @Test
    fun `업로드하지 않은 key 로 confirm 하면 400 으로 거부되고 아이템이 생기지 않는다`() {
        val mockMvc = buildMockMvc()
        val ownerId = UUID.randomUUID()
        insertGuest(ownerId)
        var tournamentId = 0L
        try {
            // S3 에 실제로 올라오지 않은 상황 재현 — HEAD 존재확인이 false 를 돌려준다.
            stubImageStorage.existsBehavior = { false }
            tournamentId = createTournament(mockMvc, ownerId)
            val keys = presignAndGetKeys(mockMvc, ownerId, tournamentId, listOf("image/png"))
            val body = objectMapper.writeValueAsString(mapOf("imageKeys" to keys))

            mockMvc
                .perform(
                    post("/api/v1/tournaments/$tournamentId/items/images/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(ownerId)}")
                        .content(body),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("UPLOAD-002"))
                .andExpect(jsonPath("$.detail").value("아직 업로드되지 않은 이미지예요. 업로드를 마친 뒤 다시 시도해 주세요."))

            val count =
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tournament_items WHERE tournament_id = ?",
                    Int::class.java,
                    tournamentId,
                )
            assertEquals(0, count)
        } finally {
            stubImageStorage.existsBehavior = stubImageStorage.defaultExistsBehavior
            cleanup(ownerId, tournamentId)
        }
    }

    @Test
    fun `지원하지 않는 content-type 으로 발급하면 400 으로 거부된다`() {
        val mockMvc = buildMockMvc()
        val ownerId = UUID.randomUUID()
        insertGuest(ownerId)
        var tournamentId = 0L
        try {
            tournamentId = createTournament(mockMvc, ownerId)
            val body = objectMapper.writeValueAsString(mapOf("contentTypes" to listOf("application/pdf")))
            mockMvc
                .perform(
                    post("/api/v1/tournaments/$tournamentId/items/images/presigned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(ownerId)}")
                        .content(body),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("PRODUCTIMAGE-003"))
                .andExpect(jsonPath("$.detail").value("지원하지 않는 이미지 형식이에요."))
        } finally {
            cleanup(ownerId, tournamentId)
        }
    }

    @Test
    fun `content-type 이 6개면 개수 위반으로 400 이 반환된다`() {
        val mockMvc = buildMockMvc()
        val ownerId = UUID.randomUUID()
        insertGuest(ownerId)
        var tournamentId = 0L
        try {
            tournamentId = createTournament(mockMvc, ownerId)
            val body = objectMapper.writeValueAsString(mapOf("contentTypes" to List(6) { "image/png" }))
            mockMvc
                .perform(
                    post("/api/v1/tournaments/$tournamentId/items/images/presigned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(ownerId)}")
                        .content(body),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.detail").value("이미지는 1~5장만 올릴 수 있어요."))
        } finally {
            cleanup(ownerId, tournamentId)
        }
    }

    @Test
    fun `발급 형식이 아닌 key 로 confirm 하면 400 으로 거부된다`() {
        val mockMvc = buildMockMvc()
        val ownerId = UUID.randomUUID()
        insertGuest(ownerId)
        var tournamentId = 0L
        try {
            tournamentId = createTournament(mockMvc, ownerId)
            val body = objectMapper.writeValueAsString(mapOf("imageKeys" to listOf("items/raw/not-a-uuid.png")))
            mockMvc
                .perform(
                    post("/api/v1/tournaments/$tournamentId/items/images/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(ownerId)}")
                        .content(body),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("UPLOAD-001"))
                .andExpect(jsonPath("$.detail").value("올바르지 않은 이미지 업로드 정보예요. 업로드를 다시 시도해 주세요."))
        } finally {
            cleanup(ownerId, tournamentId)
        }
    }

    // ---- 헬퍼 ----

    private fun createTournament(
        mockMvc: MockMvc,
        ownerId: UUID,
    ): Long {
        // 토너먼트 생성 시 TournamentUser(owner)도 함께 생성되어 verifyCanAddItems 의 참여자 검증을 통과한다.
        val response =
            mockMvc
                .perform(
                    post("/api/v1/tournaments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(ownerId)}")
                        .content("""{"name":"프리사인토너먼트"}"""),
                ).andReturn()
                .response
                .getContentAsString(Charsets.UTF_8)
        return objectMapper
            .readTree(response)
            .path("data")
            .path("tournamentId")
            .asLong()
    }

    private fun presignAndGetKeys(
        mockMvc: MockMvc,
        ownerId: UUID,
        tournamentId: Long,
        contentTypes: List<String>,
    ): List<String> {
        val body = objectMapper.writeValueAsString(mapOf("contentTypes" to contentTypes))
        val response =
            mockMvc
                .perform(
                    post("/api/v1/tournaments/$tournamentId/items/images/presigned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(ownerId)}")
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

    private fun insertGuest(userId: UUID) {
        jdbcTemplate.update(
            "INSERT INTO users (id, nickname, identity_type, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
            uuidToBytes(userId),
            userId.toString().take(10),
            "GUEST",
        )
    }

    private fun token(userId: UUID): String = jwtProvider.generateAccessToken(userId, IdentityType.GUEST)

    private fun cleanup(
        ownerId: UUID,
        tournamentId: Long,
    ) {
        if (tournamentId != 0L) {
            val itemIds =
                jdbcTemplate.queryForList(
                    "SELECT s.item_id FROM tournament_items ti JOIN item_snapshots s ON s.id = ti.snapshot_id WHERE ti.tournament_id = ?",
                    Long::class.java,
                    tournamentId,
                )
            jdbcTemplate.update("DELETE FROM tournament_items WHERE tournament_id = ?", tournamentId)
            jdbcTemplate.update("DELETE FROM tournament_users WHERE tournament_id = ?", tournamentId)
            jdbcTemplate.update("DELETE FROM tournaments WHERE id = ?", tournamentId)
            itemIds.takeIf { it.isNotEmpty() }?.let {
                jdbcTemplate.update("DELETE FROM item_snapshots WHERE item_id IN (${it.joinToString(",")})")
                jdbcTemplate.update("DELETE FROM items WHERE id IN (${it.joinToString(",")})")
            }
        }
        // presign 만 하고 confirm 을 안 한(또는 confirm 이 400 인) 케이스의 pending_uploads 가 남지 않게 함께 지운다(FK 는 없음, orphan 위생).
        jdbcTemplate.update("DELETE FROM pending_uploads WHERE user_id = ?", uuidToBytes(ownerId))
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(ownerId))
    }

    // confirm 이 만든 PENDING 을 자동 dispatch(1s)가 파싱 중일 수 있어, 삭제-vs-워커 UPDATE 레이스를 피하려면
    // 후속 처리가 terminal(READY/FAILED)로 끝난 뒤 cleanup 한다.
    private fun awaitDispatchSettled(tournamentId: Long) {
        await().atMost(Duration.ofSeconds(5)).until {
            val inFlight =
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM item_snapshots s JOIN tournament_items ti ON ti.snapshot_id = s.id " +
                        "WHERE ti.tournament_id = ? AND s.status IN ('PENDING', 'PROCESSING')",
                    Int::class.java,
                    tournamentId,
                ) ?: 0
            inFlight == 0
        }
    }
}
