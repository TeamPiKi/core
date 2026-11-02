package com.depromeet.piki.folder.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.folder.domain.FolderErrorCode
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertEquals

// 폴더 편집의 "전체 선택" 뒤 빼기 · 옮기기(#1272). 개수 제한(100개) 없이 폴더 전부를 처리하는지 본다
@Transactional
class FolderSelectAllIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var entityManager: EntityManager

    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var jwtProvider: JwtProvider

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun member(): UUID =
        UUID.randomUUID().also {
            jdbcTemplate.update(
                "INSERT INTO users (id, nickname, identity_type, created_at, updated_at) VALUES (?, ?, 'MEMBER', NOW(6), NOW(6))",
                uuidToBytes(it),
                it.toString().take(10),
            )
        }

    private fun MockHttpServletRequestBuilder.by(userId: UUID): MockHttpServletRequestBuilder =
        header(HttpHeaders.AUTHORIZATION, "Bearer ${jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)}")

    private fun MockHttpServletRequestBuilder.json(body: Any): MockHttpServletRequestBuilder =
        contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))

    private fun createFolder(
        userId: UUID,
        name: String,
    ): Long {
        val body =
            mockMvc()
                .perform(post("/api/v1/folders").by(userId).json(mapOf("name" to name)))
                .andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body).path("data").path("id").asLong()
    }

    // 100개 제한을 넘는 폴더를 빠르게 만든다. 파싱 전 위시(상품 미정)로 충분하다
    private fun fillFolder(
        userId: UUID,
        folderId: Long,
        count: Int,
    ): List<Long> =
        (1..count).map {
            jdbcTemplate.update("INSERT INTO wishes (user_id, created_at, updated_at) VALUES (?, NOW(6), NOW(6))", uuidToBytes(userId))
            val wishId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long::class.java)!!
            jdbcTemplate.update(
                "INSERT INTO folder_wishes (folder_id, wish_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                folderId,
                wishId,
            )
            wishId
        }

    private fun linkCount(folderId: Long): Long {
        entityManager.flush()
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM folder_wishes WHERE folder_id = ?", Long::class.java, folderId)!!
    }

    @Test
    fun `전체 선택으로 빼면 100개가 넘는 폴더도 한 번에 비고 위시는 남는다`() {
        val userId = member()
        val bag = createFolder(userId, "가방")
        val wishIds = fillFolder(userId, bag, 101)

        mockMvc()
            .perform(delete("/api/v1/folders/$bag/wishes").param("all", "true").by(userId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.wishCount").value(0))

        assertEquals(0L, linkCount(bag))
        val liveWishes =
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wishes WHERE id IN (${wishIds.joinToString()}) AND deleted_at IS NULL", Long::class.java)
        assertEquals(101L, liveWishes)
    }

    @Test
    fun `전체 선택으로 옮기면 100개가 넘는 위시가 대상 폴더로 모두 옮겨진다`() {
        val userId = member()
        val bag = createFolder(userId, "가방")
        val gift = createFolder(userId, "선물")
        fillFolder(userId, bag, 101)

        mockMvc()
            .perform(post("/api/v1/folders/$bag/wishes/move").by(userId).json(mapOf("all" to true, "targetFolderId" to gift)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.wishCount").value(0))
            .andExpect(jsonPath("$.data.targetWishCount").value(101))

        assertEquals(0L, linkCount(bag))
        assertEquals(101L, linkCount(gift))
    }

    @Test
    fun `all 과 wishIds 를 함께 보내면 400 FOLDER-005 이고 폴더는 그대로다`() {
        val userId = member()
        val bag = createFolder(userId, "가방")
        val wishIds = fillFolder(userId, bag, 2)

        mockMvc()
            .perform(delete("/api/v1/folders/$bag/wishes").param("all", "true").param("wishIds", wishIds[0].toString()).by(userId))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(FolderErrorCode.INVALID_SELECTION.code))
            .andExpect(jsonPath("$.detail").value(FolderErrorCode.INVALID_SELECTION.message))

        assertEquals(2L, linkCount(bag))
    }
}
