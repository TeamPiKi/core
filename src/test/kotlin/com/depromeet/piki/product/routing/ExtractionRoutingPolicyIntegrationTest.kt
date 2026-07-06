package com.depromeet.piki.product.routing

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubProductLinkExtractor
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID
import kotlin.test.assertEquals

// 추출 라우팅 정책(#9 디스패처)이 DB + 캐시 reload 로 배포 없이 등록 판정을 바꾸는지 검증한다.
// @Transactional 자동 롤백을 쓰지 않는다 — 정책 캐시(@Volatile)는 롤백으로 되돌아가지 않아 다른 테스트로 누수되므로,
// 각 테스트가 자기 행을 명시적으로 정리하고 reload() 로 캐시를 시드 상태로 복원한다(finally).
class ExtractionRoutingPolicyIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Autowired
    private lateinit var policyRepository: ExtractionPlatformPolicyJpaRepository

    @Autowired
    private lateinit var routingPolicy: DbExtractionRoutingPolicy

    @Autowired
    private lateinit var stubProductLinkExtractor: StubProductLinkExtractor

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    @Test
    fun `UNSUPPORTED 정책을 추가하면 등록이 400 으로 거부되고, 삭제하면 같은 URL 이 다시 통과한다`() {
        val mockMvc = mockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        // 도메인은 테스트 격리용 유니크 값 — 서브도메인(shop.)까지 매칭되는지 함께 본다.
        val domain = "blocked-${UUID.randomUUID()}.example.com"
        val body = """{"url": "https://shop.$domain/p/1"}"""
        stubProductLinkExtractor.build = { ProductSnapshot(link = it, name = "테스트 상품", currentPrice = 9_900) }
        try {
            // 정책 추가 + reload — 배포 없이 곧바로 등록이 거부된다(백오피스 저장 → afterCommit reload 와 같은 경로).
            policyRepository.save(ExtractionPlatformPolicyEntity(domain = domain, route = ExtractionRoute.UNSUPPORTED, reason = "테스트"))
            routingPolicy.reload()

            mockMvc
                .perform(
                    post("/api/v1/wishlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                        .content(body),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.detail").value("아직 지원하지 않는 쇼핑몰이에요. 상품 이미지를 직접 등록해 주세요."))

            // 정책 삭제 + reload — 차단이 풀리면(봇 차단은 변동적) 행만 지워 되돌린다. 같은 URL 이 이제 등록된다.
            policyRepository.deleteById(domain)
            routingPolicy.reload()

            mockMvc
                .perform(
                    post("/api/v1/wishlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                        .content(body),
                ).andExpect(status().isCreated)
        } finally {
            policyRepository.findById(domain).ifPresent { policyRepository.delete(it) } // 400 경로에서 남았을 때만
            routingPolicy.reload()
            cleanup(userId)
        }
    }

    @Test
    fun `백오피스 정책 화면이 시드 정책을 렌더하고, 추가 폼 POST 가 즉시 반영되는 정책을 만든다`() {
        val mockMvc = mockMvc()
        val domain = "added-${UUID.randomUUID()}.example.com"
        try {
            // Thymeleaf 에러는 렌더 시점에 드러난다 — 시드(coupang.com 등)가 목록에 실제로 나오는지로 렌더를 검증한다.
            mockMvc
                .perform(get("/admin/extraction-policies"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("coupang.com")))

            // 추가 폼 — 대문자·trailing dot 입력도 정규형으로 저장되고(admin 경계가 정규화), afterCommit reload 로 즉시 적용된다.
            mockMvc
                .perform(
                    post("/admin/extraction-policies")
                        .with(csrf())
                        .param("domain", "ADDED-${domain.removePrefix("added-").uppercase()}.")
                        .param("route", "UNSUPPORTED")
                        .param("reason", "테스트 사유"),
                ).andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrl("/admin/extraction-policies?updated"))

            val saved = policyRepository.findById(domain).orElseThrow()
            assertEquals(ExtractionRoute.UNSUPPORTED, saved.route)
            assertEquals("테스트 사유", saved.reason)
            assertEquals(ExtractionRoute.UNSUPPORTED, routingPolicy.routeOf(ProductLink.parse("https://$domain/p")))
        } finally {
            policyRepository.findById(domain).ifPresent { policyRepository.delete(it) }
            routingPolicy.reload()
        }
    }

    private fun insertMember(userId: UUID) {
        jdbcTemplate.update(
            "INSERT INTO users (id, nickname, identity_type, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
            uuidToBytes(userId),
            userId.toString().take(10),
            "MEMBER",
        )
    }

    private fun memberToken(userId: UUID): String = jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)

    // @Transactional 자동 롤백이 없으므로 이 테스트가 만든 user·wish·item·snapshot 을 직접 정리한다
    // (WishlistRegisterAsyncIntegrationTest 와 같은 패턴).
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
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(userId))
    }
}
