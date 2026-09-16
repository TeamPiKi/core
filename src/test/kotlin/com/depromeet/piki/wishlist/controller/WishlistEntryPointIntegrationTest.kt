package com.depromeet.piki.wishlist.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.metrics.registration.ExternalEntry
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubItemParsingWorker
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertEquals

@Transactional
class WishlistEntryPointIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Autowired
    private lateinit var stubItemParsingWorker: StubItemParsingWorker

    @Test
    fun `공유 시트로 넘어온 담기는 SHARE_SHEET 로 기록된다`() {
        val userId = UUID.randomUUID()
        insertMember(userId)

        register(userId, "https://shop.example.com/products/1", entryPoint = "SHARE_SHEET")

        assertEquals(listOf(ExternalEntry.SHARE_SHEET.name), recordedEntries(userId))
    }

    @Test
    fun `앱이나 웹에서 직접 담으면 헤더가 없고 행도 남지 않는다`() {
        val userId = UUID.randomUUID()
        insertMember(userId)

        register(userId, "https://shop.example.com/products/2", entryPoint = null)

        assertEquals(emptyList(), recordedEntries(userId))
    }

    @Test
    fun `서버가 모르는 헤더 값이 와도 등록은 201 이고 행은 남지 않는다`() {
        val userId = UUID.randomUUID()
        insertMember(userId)

        register(userId, "https://shop.example.com/products/3", entryPoint = "WIDGET")

        assertEquals(emptyList(), recordedEntries(userId))
    }

    private fun register(
        userId: UUID,
        url: String,
        entryPoint: String?,
    ) {
        val request =
            post("/api/v1/wishlists")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                .content(objectMapper.writeValueAsString(mapOf("url" to url)))
        entryPoint?.let { request.header(ExternalEntry.HEADER, it) }

        // 파싱은 이 테스트의 관심사가 아니고, 켜 두면 워커가 미커밋 item 을 읽어 warn 을 쏟는다.
        stubItemParsingWorker.enabled = false
        try {
            buildMockMvc().perform(request).andExpect(status().isCreated)
        } finally {
            stubItemParsingWorker.enabled = true
        }
    }

    private fun recordedEntries(userId: UUID): List<String?> =
        jdbcTemplate.queryForList(
            """
            SELECT e.entry_point FROM wish_external_entries e
            JOIN wishes w ON w.id = e.wish_id
            WHERE w.user_id = ? ORDER BY e.id
            """.trimIndent(),
            String::class.java,
            uuidToBytes(userId),
        )

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
