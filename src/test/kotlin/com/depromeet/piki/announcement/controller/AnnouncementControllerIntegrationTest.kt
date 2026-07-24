package com.depromeet.piki.announcement.controller

import com.depromeet.piki.announcement.domain.Announcement
import com.depromeet.piki.announcement.repository.AnnouncementRepository
import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.user.domain.IdentityType
import com.jayway.jsonpath.JsonPath
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

@Transactional
class AnnouncementControllerIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Autowired private lateinit var announcementRepository: AnnouncementRepository

    private fun authHeader(
        userId: UUID = UUID.randomUUID(),
        identityType: IdentityType = IdentityType.MEMBER,
    ): String = "Bearer ${jwtProvider.generateAccessToken(userId, identityType)}"

    private fun buildMockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    // 발송 완료(SENT) 공지 — DRAFT → SENDING → SENT 전이로 sentAt 까지 채운다.
    private fun sent(
        title: String = "점검 안내",
        body: String = "본문",
    ): Announcement =
        announcementRepository.save(
            Announcement(title = title, body = body, target = "토큰 보유자 전체").apply {
                markSending(recipientCount = 1, sentBy = "tester")
                markSent()
            },
        )

    // 미발송(DRAFT) 공지 — 유저에게 노출되면 안 된다.
    private fun draft(): Announcement = announcementRepository.save(Announcement(title = "초안", body = "본문", target = "전체"))

    @Test
    fun `SENT 공지를 id 로 조회하면 200 과 본문이 ApiResponseBody contract 로 내려온다`() {
        val announcement = sent(title = "서비스 점검 안내", body = "6월 20일 점검 예정")

        buildMockMvc()
            .perform(get("/api/v1/announcements/${announcement.getId()}").header(HttpHeaders.AUTHORIZATION, authHeader()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(announcement.getId()))
            .andExpect(jsonPath("$.data.title").value("서비스 점검 안내"))
            .andExpect(jsonPath("$.data.body").value("6월 20일 점검 예정"))
            .andExpect(jsonPath("$.data.sentAt", notNullValue()))
    }

    @Test
    fun `미발송 공지나 없는 id 는 단건 조회 시 404 다 (미발송 존재를 노출하지 않음)`() {
        val draft = draft()
        val mockMvc = buildMockMvc()

        // DRAFT — 존재하지만 SENT 가 아니라 404
        mockMvc
            .perform(get("/api/v1/announcements/${draft.getId()}").header(HttpHeaders.AUTHORIZATION, authHeader()))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("ANNOUNCEMENT-001"))

        // 없는 id — 404
        mockMvc
            .perform(get("/api/v1/announcements/999999").header(HttpHeaders.AUTHORIZATION, authHeader()))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("ANNOUNCEMENT-001"))
    }

    @Test
    fun `목록은 SENT 만 최신순으로 내려오고 미발송은 제외된다`() {
        val older = sent(title = "오래된 공지")
        val newer = sent(title = "최신 공지")
        draft() // 미발송 — 목록에 섞이면 안 됨

        buildMockMvc()
            .perform(get("/api/v1/announcements").header(HttpHeaders.AUTHORIZATION, authHeader()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(2))
            // 최신순(id desc): newer → older
            .andExpect(jsonPath("$.data[0].id").value(newer.getId()))
            .andExpect(jsonPath("$.data[1].id").value(older.getId()))
            .andExpect(jsonPath("$.pageResponse.hasNext").value(false))
            .andExpect(jsonPath("$.pageResponse.nextCursor").value(nullValue()))
    }

    @Test
    fun `목록은 size 로 페이지를 나누고 nextCursor 로 다음 페이지를 잇는다`() {
        val first = sent(title = "1")
        val second = sent(title = "2")
        val third = sent(title = "3")
        val mockMvc = buildMockMvc()

        // 1페이지: size=2 → 발송순 최신 2건(third, second), 다음 페이지 있음. nextCursor 는 opaque 토큰이라 값을 받아 다음 요청에 그대로 넘긴다.
        val page1 =
            mockMvc
                .perform(get("/api/v1/announcements").param("size", "2").header(HttpHeaders.AUTHORIZATION, authHeader()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(third.getId()))
                .andExpect(jsonPath("$.data[1].id").value(second.getId()))
                .andExpect(jsonPath("$.pageResponse.hasNext").value(true))
                .andExpect(jsonPath("$.pageResponse.nextCursor", notNullValue()))
                .andReturn()
        val nextCursor: String = JsonPath.read(page1.response.getContentAsString(Charsets.UTF_8), "$.pageResponse.nextCursor")

        // 2페이지: 1페이지가 준 커서 → 남은 1건(first), 다음 페이지 없음
        mockMvc
            .perform(
                get("/api/v1/announcements")
                    .param("size", "2")
                    .param("cursor", nextCursor)
                    .header(HttpHeaders.AUTHORIZATION, authHeader()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(first.getId()))
            .andExpect(jsonPath("$.pageResponse.hasNext").value(false))
            .andExpect(jsonPath("$.pageResponse.nextCursor").value(nullValue()))
    }

    @Test
    fun `게스트도 발송된 공지를 목록·단건으로 조회할 수 있다`() {
        val announcement = sent(title = "게스트도 보는 공지")
        val mockMvc = buildMockMvc()
        val guest = authHeader(identityType = IdentityType.GUEST)

        mockMvc
            .perform(get("/api/v1/announcements").header(HttpHeaders.AUTHORIZATION, guest))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(announcement.getId()))

        mockMvc
            .perform(get("/api/v1/announcements/${announcement.getId()}").header(HttpHeaders.AUTHORIZATION, guest))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(announcement.getId()))
    }

    @Test
    fun `유효하지 않은 cursor 는 400 ANNOUNCEMENT-002 로 거른다`() {
        buildMockMvc()
            .perform(get("/api/v1/announcements").param("cursor", "abc").header(HttpHeaders.AUTHORIZATION, authHeader()))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("ANNOUNCEMENT-002"))
    }

    @Test
    fun `토큰 없이 조회하면 401 이 ApiResponseBody contract 로 내려간다`() {
        buildMockMvc()
            .perform(get("/api/v1/announcements"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.detail", notNullValue()))
    }
}
