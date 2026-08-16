package com.depromeet.piki.admin.extraction

import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.routing.DbDomainAccessPolicy
import com.depromeet.piki.product.routing.DomainAccessPolicyEntity
import com.depromeet.piki.product.routing.DomainAccessPolicyJpaRepository
import com.depromeet.piki.product.routing.DomainAccess
import com.depromeet.piki.support.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 백오피스 도메인 접근 정책 화면(갈래별 보드 · 상세)의 시나리오·렌더 contract.
// @Transactional 자동 롤백을 쓰지 않는다 — 저장·삭제가 afterCommit 에 캐시 reload 를 걸고(롤백되면 타지 않는다),
// 정책 캐시(@Volatile)는 롤백으로 되돌아가지 않아 다른 테스트로 누수된다. 각 테스트가 자기 행을 정리하고 reload 한다.
// 단언에 한글을 쓰지 않는다 — 렌더된 HTML 을 문자열로 훑으므로 응답 charset 에 결과가 좌우되지 않게 ASCII 로 고정한다.
class AdminExtractionPolicyIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var policyRepository: DomainAccessPolicyJpaRepository

    @Autowired
    private lateinit var accessPolicy: DbDomainAccessPolicy

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

    @Test
    fun `보드는 두 갈래 열을 모두 렌더하고, 사유는 목록에 노출하지 않는다`() {
        // 사유가 목록에 실리면 행이 세로로 벌어지고(개편 동기) 상세로 들어갈 이유도 사라진다.
        // 사유 전문은 상세 화면의 몫이라는 contract 를 여기서 고정한다.
        val mockMvc = mockMvc()
        val domain = "board-${UUID.randomUUID()}.example.com"
        val reason = "REASON-${UUID.randomUUID()}"
        try {
            policyRepository.save(DomainAccessPolicyEntity(domain = domain, access = DomainAccess.ALLOWED.name, reason = reason, permissionRef = "test permission"))

            val body = html(mockMvc, "/admin/extraction-policies")

            assertContains(body, domain)
            DomainAccess.entries.forEach { assertContains(body, it.name) } // 열 헤더 2개
            assertFalse(body.contains(reason), "사유는 보드가 아니라 상세에서만 보여야 한다")
        } finally {
            policyRepository.findById(domain).ifPresent { policyRepository.delete(it) }
            accessPolicy.reload()
        }
    }

    @Test
    fun `access 필터는 그 갈래만 남기고, 모르는 값이면 전체를 보여준다`() {
        // ?access=는 tolerant 하게 읽는다(옛 링크·손으로 고친 URL 이 400 으로 깨지지 않게).
        // 시드 도메인(coupang.com)으로 단언하지 않는다 — 화면 설명·입력 placeholder 에 예시로 박혀 있어
        // 필터와 무관하게 늘 HTML 에 등장하므로 거짓 통과·거짓 실패를 만든다.
        val mockMvc = mockMvc()
        val supported = "filter-ok-${UUID.randomUUID()}.example.com"
        val unsupported = "filter-no-${UUID.randomUUID()}.example.com"
        try {
            policyRepository.save(DomainAccessPolicyEntity(domain = supported, access = DomainAccess.ALLOWED.name, reason = null, permissionRef = "test permission"))
            policyRepository.save(DomainAccessPolicyEntity(domain = unsupported, access = DomainAccess.BLOCKED.name, reason = null))

            val filtered = html(mockMvc, "/admin/extraction-policies?access=ALLOWED")
            assertContains(filtered, supported)
            assertFalse(filtered.contains(unsupported), "다른 갈래의 정책은 ALLOWED 필터에 나오면 안 된다")

            val tolerated = html(mockMvc, "/admin/extraction-policies?access=NOT_AN_ACCESS")
            assertContains(tolerated, supported)
            assertContains(tolerated, unsupported)
        } finally {
            listOf(supported, unsupported).forEach { d -> policyRepository.findById(d).ifPresent { policyRepository.delete(it) } }
            accessPolicy.reload()
        }
    }

    @Test
    fun `모르는 정책은 전체 보기에만 나오고 필터를 걸면 숨는다`() {
        // 모르는 정책은 값으로 지목할 수 없어 어떤 필터에도 속하지 않는다. 필터를 건 화면에까지 끼워 넣으면
        // "그 갈래만 본다"는 약속이 깨진다. 반대로 전체 보기에서도 빠지면 보이지도 지워지지도 않는 유령 행이 된다.
        val mockMvc = mockMvc()
        val domain = "filtered-unknown-${UUID.randomUUID()}.example.com"
        try {
            policyRepository.save(DomainAccessPolicyEntity(domain = domain, access = "FUTURE_ACCESS", reason = null))

            assertContains(html(mockMvc, "/admin/extraction-policies"), domain)
            assertFalse(
                html(mockMvc, "/admin/extraction-policies?access=ALLOWED").contains(domain),
                "필터를 건 화면에는 모르는 정책이 나오면 안 된다",
            )
        } finally {
            policyRepository.findById(domain).ifPresent { policyRepository.delete(it) }
            accessPolicy.reload()
        }
    }

    @Test
    fun `추가 폼은 대문자·trailing dot 입력을 정규형으로 저장하고 즉시 반영한다`() {
        val mockMvc = mockMvc()
        val suffix = UUID.randomUUID()
        val domain = "added-$suffix.example.com"
        try {
            mockMvc
                .perform(
                    post("/admin/extraction-policies")
                        .with(csrf())
                        .param("domain", "ADDED-${suffix.toString().uppercase()}.EXAMPLE.COM.")
                        .param("access", "BLOCKED")
                        .param("reason", "test reason"),
                ).andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrl("/admin/extraction-policies?updated"))

            val saved = policyRepository.findById(domain).orElseThrow()
            assertEquals(DomainAccess.BLOCKED.name, saved.access)
            assertEquals("test reason", saved.reason)
            // afterCommit reload 로 배포 없이 곧바로 판정에 반영된다.
            assertEquals(DomainAccess.BLOCKED, accessPolicy.accessOf(ProductLink.parse("https://$domain/p")))
        } finally {
            policyRepository.findById(domain).ifPresent { policyRepository.delete(it) }
            accessPolicy.reload()
        }
    }

    @Test
    fun `상세는 사유 전문을 싣고, 정책과 사유를 한 번에 교체한다`() {
        // 정책이 바뀌는 순간이 곧 근거가 새로 필요한 순간이라 셋(값·사유·허락 근거)을 한 폼으로 받는다 —
        // 따로 저장되면 차단 사유가 허락 행에 남아 다음 사람이 잘못된 근거를 믿는다.
        val mockMvc = mockMvc()
        val domain = "detail-${UUID.randomUUID()}.example.com"
        val oldReason = "OLD-${UUID.randomUUID()}"
        val newReason = "NEW-${UUID.randomUUID()}"
        try {
            policyRepository.save(DomainAccessPolicyEntity(domain = domain, access = DomainAccess.BLOCKED.name, reason = oldReason))
            accessPolicy.reload()

            // 도메인에 점이 들어간 path variable 이 잘리지 않고 상세로 매핑된다.
            val detail = html(mockMvc, "/admin/extraction-policies/$domain")
            assertContains(detail, domain)
            assertContains(detail, oldReason)

            mockMvc
                .perform(
                    post("/admin/extraction-policies/$domain")
                        .with(csrf())
                        .param("access", "ALLOWED")
                        .param("reason", newReason)
                        .param("permissionRef", "partner mail thread"),
                ).andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrl("/admin/extraction-policies?updated"))

            val saved = policyRepository.findById(domain).orElseThrow()
            assertEquals(DomainAccess.ALLOWED.name, saved.access)
            assertEquals(newReason, saved.reason)
            assertEquals(DomainAccess.ALLOWED, accessPolicy.accessOf(ProductLink.parse("https://$domain/p")))
        } finally {
            policyRepository.findById(domain).ifPresent { policyRepository.delete(it) }
            accessPolicy.reload()
        }
    }

    @Test
    fun `상세에서 삭제하면 정책이 사라지고 그 도메인은 기본 추출로 돌아간다`() {
        val mockMvc = mockMvc()
        val domain = "deleted-${UUID.randomUUID()}.example.com"
        try {
            policyRepository.save(DomainAccessPolicyEntity(domain = domain, access = DomainAccess.BLOCKED.name, reason = null))
            accessPolicy.reload()

            mockMvc
                .perform(post("/admin/extraction-policies/$domain/delete").with(csrf()))
                .andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrl("/admin/extraction-policies?deleted"))

            assertTrue(policyRepository.findById(domain).isEmpty)
            // 정책 없음 = 기본 체인. 삭제 즉시(afterCommit reload) 등록이 다시 열린다.
            assertNull(accessPolicy.accessOf(ProductLink.parse("https://$domain/p")))
        } finally {
            policyRepository.findById(domain).ifPresent { policyRepository.delete(it) }
            accessPolicy.reload()
        }
    }






    @Test
    fun `정책이 없는 도메인의 상세는 보드로 리다이렉트된다`() {
        // 다른 운영자가 방금 지웠거나 URL 을 손으로 친 경우 — 500 대신 보드로 돌려보낸다.
        mockMvc()
            .perform(get("/admin/extraction-policies/missing-${UUID.randomUUID()}.example.com"))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/admin/extraction-policies?missing"))
    }

    @Test
    fun `이 서버가 모르는 정책 값 행도 보드에 노출되고 상세에서 지울 수 있다`() {
        // 신버전이 만든 정책을 구버전으로 롤백하면 생긴다(DB 는 forward-only). 판정은 tolerant 하게 기본 흐름으로
        // 떨어지지만, 화면이 이 행을 어느 열에도 안 넣으면 백오피스에서 보이지도 지워지지도 않는 유령 행이 된다.
        val mockMvc = mockMvc()
        val domain = "unknown-${UUID.randomUUID()}.example.com"
        val futureRoute = "FUTURE_ACCESS"
        try {
            policyRepository.save(DomainAccessPolicyEntity(domain = domain, access = futureRoute, reason = null))
            accessPolicy.reload()

            assertNull(accessPolicy.accessOf(ProductLink.parse("https://$domain/p")), "모르는 route 는 기본 체인으로 떨어진다")

            val board = html(mockMvc, "/admin/extraction-policies")
            assertContains(board, domain)
            assertContains(board, futureRoute)

            val detail = html(mockMvc, "/admin/extraction-policies/$domain")
            assertContains(detail, futureRoute)

            mockMvc
                .perform(post("/admin/extraction-policies/$domain/delete").with(csrf()))
                .andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrl("/admin/extraction-policies?deleted"))
            assertTrue(policyRepository.findById(domain).isEmpty)
        } finally {
            policyRepository.findById(domain).ifPresent { policyRepository.delete(it) }
            accessPolicy.reload()
        }
    }
}
