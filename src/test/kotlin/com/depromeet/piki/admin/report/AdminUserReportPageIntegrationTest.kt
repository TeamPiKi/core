package com.depromeet.piki.admin.report

import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.userreport.domain.ReportReason
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 백오피스 신고 화면(#1291)의 렌더 contract. 목록은 누적 수를, 상세는 그 사람이 받은 신고 전체를 보여야 한다.
// 단언은 id·숫자·ASCII 로 고정한다 — 렌더된 HTML 을 문자열로 훑으므로 응답 charset 에 결과가 좌우되지 않게.
@Transactional
class AdminUserReportPageIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `목록 행은 대상 상세로 이어진다`() {
        val mockMvc = mockMvc()
        val reported = member()
        insertReport(member(), reported, ReportReason.SPAM_OR_AD)

        val html = html(mockMvc, "/admin/reports")

        assertContains(html, reported.toString(), message = "신고된 사용자 id 가 행에 있어야 한다")
        assertContains(html, "/admin/reports/$reported", message = "행이 대상 상세로 이어져야 한다")
    }

    // 여러 번 신고된 사람을 훑으며 집어낼 단서다. 누적 배지 강조(many)로 고정한다 —
    // 숫자만 단언하면 HTML 어디에 있는 같은 숫자와도 맞아 통과해버린다
    @Test
    fun `누적 2건 이상인 대상만 목록에서 강조된다`() {
        val mockMvc = mockMvc()
        val once = member()
        insertReport(member(), once, ReportReason.SPAM_OR_AD)

        assertFalse(html(mockMvc, "/admin/reports").contains("cnt many"), "1건은 강조하지 않는다")

        insertReport(member(), once, ReportReason.IMPERSONATION)
        assertContains(html(mockMvc, "/admin/reports"), "cnt many", message = "2건부터 강조해야 한다")
    }

    @Test
    fun `상세는 그 사람이 받은 신고 전체와 기타 상세 내용을 보여준다`() {
        val mockMvc = mockMvc()
        val reported = member()
        insertReport(member(), reported, ReportReason.SPAM_OR_AD)
        insertReport(member(), reported, ReportReason.OTHER, detail = "detail-marker-42")

        val html = html(mockMvc, "/admin/reports/$reported")

        assertContains(html, "detail-marker-42", message = "기타 상세 내용이 보여야 한다")
        assertContains(html, reported.toString(), message = "대상 요약에 id 가 있어야 한다")
    }

    // 다른 사람이 받은 신고가 섞이면 운영이 엉뚱한 사람을 제재한다
    @Test
    fun `상세는 다른 사람이 받은 신고를 섞지 않는다`() {
        val mockMvc = mockMvc()
        val reported = member()
        val other = member()
        insertReport(member(), reported, ReportReason.OTHER, detail = "mine-marker")
        insertReport(member(), other, ReportReason.OTHER, detail = "others-marker")

        val html = html(mockMvc, "/admin/reports/$reported")

        assertTrue(html.contains("mine-marker"), "자기 신고는 보여야 한다")
        assertFalse(html.contains("others-marker"), "다른 사람 신고가 섞이면 안 된다")
    }

    @Test
    fun `신고도 사용자도 없는 id 는 목록으로 되돌린다`() {
        val mockMvc = mockMvc()

        mockMvc
            .perform(get("/admin/reports/${UUID.randomUUID()}"))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/admin/reports?missing"))
    }

    // SSR 이라 잘못된 경로 변수에 예외를 올리면 응답이 화면 대신 raw JSON 으로 갈아치워진다
    @Test
    fun `UUID 가 아닌 id 도 목록으로 되돌린다`() {
        val mockMvc = mockMvc()

        mockMvc
            .perform(get("/admin/reports/not-a-uuid"))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/admin/reports?missing"))
    }

    // 신고 기록은 탈퇴해도 남는다(#1291). 대상이 탈퇴했다고 행이 사라지면 패턴을 못 본다
    @Test
    fun `탈퇴한 대상의 신고도 목록과 상세에 남는다`() {
        val mockMvc = mockMvc()
        val reported = member()
        insertReport(member(), reported, ReportReason.ABUSIVE_OR_HATE_SPEECH, detail = null)
        jdbcTemplate.update("UPDATE users SET deleted_at = NOW(6) WHERE id = ?", uuidToBytes(reported))

        assertContains(html(mockMvc, "/admin/reports"), reported.toString())
        assertContains(html(mockMvc, "/admin/reports/$reported"), reported.toString())
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun html(
        mockMvc: MockMvc,
        url: String,
    ): String =
        mockMvc
            .perform(get(url))
            .andExpect(status().isOk)
            .andReturn()
            .response.contentAsString

    private fun insertReport(
        reporterId: UUID,
        reportedId: UUID,
        reason: ReportReason,
        detail: String? = null,
    ) {
        jdbcTemplate.update(
            "INSERT INTO user_reports (reporter_id, reported_id, reason, detail, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
            uuidToBytes(reporterId),
            uuidToBytes(reportedId),
            reason.name,
            detail,
        )
    }

    private fun member(): UUID =
        UUID.randomUUID().also { userId ->
            jdbcTemplate.update(
                "INSERT INTO users (id, nickname, identity_type, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
                uuidToBytes(userId),
                userId.toString().take(10),
                IdentityType.MEMBER.name,
            )
        }
}
