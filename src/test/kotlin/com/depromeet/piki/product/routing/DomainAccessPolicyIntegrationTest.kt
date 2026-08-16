package com.depromeet.piki.product.routing

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubProductLinkExtractor
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 추출 라우팅 정책(#9 디스패처)이 DB + 캐시 reload 로 배포 없이 등록 판정을 바꾸는지 검증한다.
// @Transactional 자동 롤백을 쓰지 않는다 — 정책 캐시(@Volatile)는 롤백으로 되돌아가지 않아 다른 테스트로 누수되므로,
// 각 테스트가 자기 행을 명시적으로 정리하고 reload() 로 캐시를 시드 상태로 복원한다(finally).
class DomainAccessPolicyIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Autowired
    private lateinit var policyRepository: DomainAccessPolicyJpaRepository

    @Autowired
    private lateinit var accessPolicy: DbDomainAccessPolicy

    @Autowired
    private lateinit var stubProductLinkExtractor: StubProductLinkExtractor

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    @Test
    fun `정책 행이 없으면 허락도 차단도 아니다 - 행을 넣으면 즉시 반영된다`() {
        // "행 없음 = 기본" 규약과 그 반대 축(값을 넣으면 캐시 reload 로 즉시 반영)을 한 자리에서 고정한다.
        val domain = "permission-${UUID.randomUUID()}.example.com"
        val link = ProductLink.parse("https://shop.$domain/p/1")
        try {
            assertFalse(accessPolicy.authorizedFor(link), "행이 없으면 허락이 아니다")
            assertFalse(accessPolicy.blocked(link), "행이 없으면 차단도 아니다")

            policyRepository.save(
                DomainAccessPolicyEntity(
                    domain = domain,
                    access = DomainAccess.ALLOWED.name,
                    reason = null,
                    permissionRef = "test permission",
                ),
            )
            accessPolicy.reload()

            assertTrue(accessPolicy.authorizedFor(link), "허락 행은 서브도메인까지 허락으로 판정돼야 한다")
            assertFalse(accessPolicy.blocked(link), "허락은 차단이 아니다")
            assertEquals(DomainAccess.ALLOWED, accessPolicy.accessOf(link))
        } finally {
            policyRepository.findById(domain).ifPresent { policyRepository.delete(it) }
            accessPolicy.reload()
        }
    }

    @Test
    fun `부모 도메인과 서브도메인 정책이 겹치면 더 구체적인(긴) 도메인의 정책이 이긴다`() {
        // 최장 매치가 없으면 승자가 enum 선언 순서로 정해져, 서브도메인만 열어 주는 운영 시나리오
        // (부모 차단 유지 + 서브도메인 직행)가 조용히 무시된다.
        val parent = "overlap-${UUID.randomUUID()}.example.com"
        val sub = "m.$parent"
        try {
            policyRepository.save(DomainAccessPolicyEntity(domain = parent, access = DomainAccess.BLOCKED.name, reason = null))
            // ALLOWED 는 근거 없이는 만들 수 없다(엔티티 불변식) — 서브도메인만 열어 주는 시나리오다.
            policyRepository.save(
                DomainAccessPolicyEntity(
                    domain = sub,
                    access = DomainAccess.ALLOWED.name,
                    reason = null,
                    permissionRef = "test permission",
                ),
            )
            accessPolicy.reload()

            assertEquals(DomainAccess.ALLOWED, accessPolicy.accessOf(ProductLink.parse("https://$sub/p/1")))
            assertEquals(DomainAccess.BLOCKED, accessPolicy.accessOf(ProductLink.parse("https://$parent/p/1")))
            assertEquals(DomainAccess.BLOCKED, accessPolicy.accessOf(ProductLink.parse("https://www.$parent/p/1")))
        } finally {
            policyRepository.findById(parent).ifPresent { policyRepository.delete(it) }
            policyRepository.findById(sub).ifPresent { policyRepository.delete(it) }
            accessPolicy.reload()
        }
    }

    @Test
    fun `BLOCKED 정책을 추가하면 등록이 400 으로 거부되고, 삭제하면 같은 URL 이 다시 통과한다`() {
        val mockMvc = mockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        // 도메인은 테스트 격리용 유니크 값 — 서브도메인(shop.)까지 매칭되는지 함께 본다.
        val domain = "blocked-${UUID.randomUUID()}.example.com"
        val body = """{"url": "https://shop.$domain/p/1"}"""
        stubProductLinkExtractor.build = { ProductSnapshot(link = it, name = "테스트 상품", price = 9_900) }
        try {
            // 정책 추가 + reload — 배포 없이 곧바로 등록이 거부된다(백오피스 저장 → afterCommit reload 와 같은 경로).
            policyRepository.save(DomainAccessPolicyEntity(domain = domain, access = DomainAccess.BLOCKED.name, reason = "테스트"))
            accessPolicy.reload()

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
            accessPolicy.reload()

            mockMvc
                .perform(
                    post("/api/v1/wishlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                        .content(body),
                ).andExpect(status().isCreated)
        } finally {
            policyRepository.findById(domain).ifPresent { policyRepository.delete(it) } // 400 경로에서 남았을 때만
            accessPolicy.reload()
            cleanup(userId)
        }
    }

    @Test
    fun `허락 정책은 등록을 막지 않는다 - 차단만 등록 경계를 막는다`() {
        // 축이 하나로 합쳐지면서 "행이 있으면 뭔가 제한된다"는 오해가 생기기 쉽다. ALLOWED 는 오히려 더 여는
        // 값이라 등록 경계와 무관해야 하는데, 누군가 verifyRegistrable 을 "정책 행이 있기만 하면 거절"로 고치면
        // 허락받은 도메인의 등록이 조용히 막힌다 — 그 회귀를 여기서 잡는다.
        val mockMvc = mockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val domain = "allowed-${UUID.randomUUID()}.example.com"
        stubProductLinkExtractor.build = { ProductSnapshot(link = it, name = "테스트 상품", price = 9_900) }
        try {
            policyRepository.save(
                DomainAccessPolicyEntity(
                    domain = domain,
                    access = DomainAccess.ALLOWED.name,
                    reason = null,
                    permissionRef = "test permission",
                ),
            )
            accessPolicy.reload()

            mockMvc
                .perform(
                    post("/api/v1/wishlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                        .content("""{"url": "https://shop.$domain/p/1"}"""),
                ).andExpect(status().isCreated)
        } finally {
            policyRepository.findById(domain).ifPresent { policyRepository.delete(it) }
            accessPolicy.reload()
            cleanup(userId)
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
