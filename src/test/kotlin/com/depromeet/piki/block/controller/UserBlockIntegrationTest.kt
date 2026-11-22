package com.depromeet.piki.block.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.block.domain.UserBlockErrorCode
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.UserErrorCode
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

// 차단(#1289)의 계약. 차단이 친구를 끊는 것과 "차단 사실이 드러나지 않는 같은 응답" 을 고정한다.
@Transactional
class UserBlockIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Test
    fun `차단하면 두 사람 사이 친구 관계가 양방향으로 끊긴다`() {
        val mockMvc = mockMvc()
        val me = member()
        val other = member()
        // 서로 친구인 상태를 만든다
        mockMvc.perform(post("/api/v1/friends/$other").by(me)).andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/friends/$me").by(other)).andExpect(status().isOk)

        mockMvc.perform(post("/api/v1/blocks/$other").by(me)).andExpect(status().isOk)

        // 한쪽만 끊으면 차단당한 사람의 목록에 내가 남아 공개 폴더 수를 계속 본다
        mockMvc
            .perform(get("/api/v1/friends").by(me))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.friendCount").value(0))
        mockMvc
            .perform(get("/api/v1/friends").by(other))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.friendCount").value(0))
    }

    @Test
    fun `차단을 해제해도 친구 관계는 되살아나지 않는다`() {
        val mockMvc = mockMvc()
        val me = member()
        val other = member()
        mockMvc.perform(post("/api/v1/friends/$other").by(me)).andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/blocks/$other").by(me)).andExpect(status().isOk)

        mockMvc.perform(delete("/api/v1/blocks/$other").by(me)).andExpect(status().isOk)

        // 되살리면 차단당한 쪽은 자기도 모르게 친구가 다시 생긴다. 해제 뒤엔 친구 추가 버튼이 보이는 것이 기획 화면
        mockMvc
            .perform(get("/api/v1/friends").by(me))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.friendCount").value(0))
    }

    @Test
    fun `차단한 상대는 친구로 추가할 수 없고 응답이 404 다`() {
        val mockMvc = mockMvc()
        val me = member()
        val other = member()
        mockMvc.perform(post("/api/v1/blocks/$other").by(me)).andExpect(status().isOk)

        mockMvc
            .perform(post("/api/v1/friends/$other").by(me))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(UserErrorCode.NOT_FOUND.code))
    }

    // 차단당한 쪽이 다른 응답을 받으면 "저 사람이 나를 차단했다" 가 드러난다. 양쪽이 같은 404 여야 한다
    @Test
    fun `차단당한 쪽도 친구 추가에서 같은 404 를 받는다`() {
        val mockMvc = mockMvc()
        val blocker = member()
        val blocked = member()
        mockMvc.perform(post("/api/v1/blocks/$blocked").by(blocker)).andExpect(status().isOk)

        mockMvc
            .perform(post("/api/v1/friends/$blocker").by(blocked))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(UserErrorCode.NOT_FOUND.code))
    }

    @Test
    fun `같은 상대를 두 번 차단해도 200 이고 목록에 한 번만 남는다`() {
        val mockMvc = mockMvc()
        val me = member()
        val other = member()

        mockMvc.perform(post("/api/v1/blocks/$other").by(me)).andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/blocks/$other").by(me)).andExpect(status().isOk)

        mockMvc
            .perform(get("/api/v1/blocks").by(me))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.blocked", hasSize<Any>(1)))
    }

    @Test
    fun `차단한 적 없는 상대를 해제해도 200 이다`() {
        val mockMvc = mockMvc()
        val me = member()
        val stranger = member()

        mockMvc.perform(delete("/api/v1/blocks/$stranger").by(me)).andExpect(status().isOk)
    }

    @Test
    fun `자기 자신은 차단할 수 없다`() {
        val mockMvc = mockMvc()
        val me = member()

        mockMvc
            .perform(post("/api/v1/blocks/$me").by(me))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(UserBlockErrorCode.SELF_NOT_ALLOWED.code))
    }

    @Test
    fun `탈퇴한 상대를 차단하면 409 가 아니라 404 다`() {
        val mockMvc = mockMvc()
        val me = member()
        val withdrawn = member()
        jdbcTemplate.update("UPDATE users SET deleted_at = NOW(6) WHERE id = ?", uuidToBytes(withdrawn))

        mockMvc
            .perform(post("/api/v1/blocks/$withdrawn").by(me))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(UserErrorCode.NOT_FOUND.code))
    }

    @Test
    fun `게스트는 차단 기능을 쓸 수 없다`() {
        val mockMvc = mockMvc()
        val guest = UUID.randomUUID().also { insertUser(it, IdentityType.GUEST) }
        val other = member()

        mockMvc
            .perform(post("/api/v1/blocks/$other").by(guest, IdentityType.GUEST))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(UserBlockErrorCode.GUEST_CANNOT_USE.code))
        mockMvc
            .perform(get("/api/v1/blocks").by(guest, IdentityType.GUEST))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(UserBlockErrorCode.GUEST_CANNOT_USE.code))
    }

    @Test
    fun `차단 목록은 최근 차단순이고 닉네임과 프로필 이미지를 담는다`() {
        val mockMvc = mockMvc()
        val me = member()
        val first = member()
        val second = member()

        mockMvc.perform(post("/api/v1/blocks/$first").by(me)).andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/blocks/$second").by(me)).andExpect(status().isOk)

        mockMvc
            .perform(get("/api/v1/blocks").by(me))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.blocked[0].userId").value(second.toString()))
            .andExpect(jsonPath("$.data.blocked[1].userId").value(first.toString()))
            .andExpect(jsonPath("$.data.blocked[0].nickname").exists())
            .andExpect(jsonPath("$.data.blocked[0].profileImage").exists())
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun insertUser(
        userId: UUID,
        identityType: IdentityType,
    ) {
        jdbcTemplate.update(
            "INSERT INTO users (id, nickname, identity_type, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
            uuidToBytes(userId),
            userId.toString().take(10),
            identityType.name,
        )
    }

    private fun member(): UUID = UUID.randomUUID().also { insertUser(it, IdentityType.MEMBER) }

    private fun MockHttpServletRequestBuilder.by(
        userId: UUID,
        identityType: IdentityType = IdentityType.MEMBER,
    ): MockHttpServletRequestBuilder =
        header(HttpHeaders.AUTHORIZATION, "Bearer ${jwtProvider.generateAccessToken(userId, identityType)}")
}
