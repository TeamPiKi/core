package com.depromeet.piki.admin.sourceplatform

import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.source.DbSourcePlatformResolver
import com.depromeet.piki.product.source.SourcePlatformEntity
import com.depromeet.piki.product.source.SourcePlatformJpaRepository
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
import kotlin.test.assertTrue

// 백오피스 출처 몰 표시명 화면(목록 · 상세)의 시나리오·렌더 contract (#766).
// @Transactional 자동 롤백을 쓰지 않는다 — 저장·삭제가 afterCommit 에 캐시 reload 를 걸고(롤백되면 타지 않는다),
// 표시명 캐시(@Volatile)는 롤백으로 되돌아가지 않아 다른 테스트로 누수된다. 각 테스트가 자기 행을 정리하고 reload 한다.
// 단언에 한글을 쓰지 않는다 — 렌더된 HTML 을 문자열로 훑으므로 응답 charset 에 결과가 좌우되지 않게 ASCII 로 고정한다.
class AdminSourcePlatformIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var sourcePlatformRepository: SourcePlatformJpaRepository

    @Autowired
    private lateinit var sourcePlatformResolver: DbSourcePlatformResolver

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
    fun `추가 폼은 대문자·trailing dot 입력을 정규형으로 저장하고 서브도메인 포함으로 즉시 반영한다`() {
        val mockMvc = mockMvc()
        val suffix = UUID.randomUUID()
        val domain = "added-$suffix.example.com"
        val displayName = "MALL-$suffix"
        try {
            mockMvc
                .perform(
                    post("/admin/source-platforms")
                        .with(csrf())
                        .param("domain", "ADDED-${suffix.toString().uppercase()}.EXAMPLE.COM.")
                        .param("displayName", displayName),
                ).andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrl("/admin/source-platforms?updated"))

            val saved = sourcePlatformRepository.findById(domain).orElseThrow()
            assertEquals(displayName, saved.displayName)
            // afterCommit reload 로 배포 없이 곧바로 판정에 반영되고, 서브도메인 host 도 도메인 단위 매칭으로 잡힌다.
            assertEquals(displayName, sourcePlatformResolver.resolve(ProductLink.parse("https://shop.$domain/p")))

            val board = html(mockMvc, "/admin/source-platforms")
            assertContains(board, domain)
            assertContains(board, displayName)
        } finally {
            sourcePlatformRepository.findById(domain).ifPresent { sourcePlatformRepository.delete(it) }
            sourcePlatformResolver.reload()
        }
    }

    @Test
    fun `상세는 표시명을 교체하고 즉시 반영한다`() {
        val mockMvc = mockMvc()
        val domain = "detail-${UUID.randomUUID()}.example.com"
        val oldName = "OLD-${UUID.randomUUID()}"
        val newName = "NEW-${UUID.randomUUID()}"
        try {
            sourcePlatformRepository.save(SourcePlatformEntity(domain = domain, displayName = oldName))
            sourcePlatformResolver.reload()

            // 도메인에 점이 들어간 path variable 이 잘리지 않고 상세로 매핑된다.
            val detail = html(mockMvc, "/admin/source-platforms/$domain")
            assertContains(detail, domain)
            assertContains(detail, oldName)

            mockMvc
                .perform(
                    post("/admin/source-platforms/$domain")
                        .with(csrf())
                        .param("displayName", newName),
                ).andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrl("/admin/source-platforms?updated"))

            assertEquals(newName, sourcePlatformRepository.findById(domain).orElseThrow().displayName)
            assertEquals(newName, sourcePlatformResolver.resolve(ProductLink.parse("https://$domain/p")))
        } finally {
            sourcePlatformRepository.findById(domain).ifPresent { sourcePlatformRepository.delete(it) }
            sourcePlatformResolver.reload()
        }
    }

    @Test
    fun `상세에서 삭제하면 URL 에서 유도한 임시 표시명으로 돌아간다`() {
        val mockMvc = mockMvc()
        val domain = "deleted-${UUID.randomUUID()}.example.com"
        try {
            sourcePlatformRepository.save(SourcePlatformEntity(domain = domain, displayName = "GONE"))
            sourcePlatformResolver.reload()

            mockMvc
                .perform(post("/admin/source-platforms/$domain/delete").with(csrf()))
                .andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrl("/admin/source-platforms?deleted"))

            assertTrue(sourcePlatformRepository.findById(domain).isEmpty)
            // 등록 없음 = fallback. 삭제 즉시(afterCommit reload) 등록 가능 도메인의 첫 라벨로 유도된다.
            assertEquals("example", sourcePlatformResolver.resolve(ProductLink.parse("https://$domain/p")))
        } finally {
            sourcePlatformRepository.findById(domain).ifPresent { sourcePlatformRepository.delete(it) }
            sourcePlatformResolver.reload()
        }
    }

    @Test
    fun `등록이 없는 도메인의 상세는 목록으로 리다이렉트된다`() {
        // 다른 운영자가 방금 지웠거나 URL 을 손으로 친 경우 — 500 대신 목록으로 돌려보낸다.
        mockMvc()
            .perform(get("/admin/source-platforms/missing-${UUID.randomUUID()}.example.com"))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/admin/source-platforms?missing"))
    }
}
