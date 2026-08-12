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
    fun `시드된 미지원 플랫폼 7곳이 실제 정책 경로(마이그레이션 시드 → 캐시)로 전부 UNSUPPORTED 로 판정된다`() {
        // 시드 SQL 의 도메인 오타·누락은 컴파일·단위 테스트로 안 드러난다 — 실제 출처(Flyway 시드)를 여기서 고정해
        // 차단이 조용히 풀리는 회귀를 CI 빨간불로 만든다. 서브도메인 포함(www.) 매칭도 함께 본다.
        val seeded = listOf("kream.co.kr", "coupang.com", "naver.com", "naver.me", "oliveyoung.co.kr", "oy.run", "a-bly.com")
        seeded.forEach { domain ->
            assertEquals(
                ExtractionRoute.UNSUPPORTED,
                routingPolicy.routeOf(ProductLink.parse("https://www.$domain/p/1")),
                "$domain 시드 누락 또는 오타",
            )
        }
    }

    @Test
    fun `시드된 미지원 플랫폼은 헤드리스 허가가 꺼진 채로 남는다 - 마이그레이션 기본값이 곧 default-deny`() {
        // 허가 컬럼을 더한 마이그레이션이 기존 행을 건드리지 않았는지 실제 DB 로 확인한다. 기본값이 TRUE 로
        // 잘못 들어가면 차단 플랫폼 전부가 조용히 브라우저로 열리는 셈이 된다 — 그 회귀를 CI 빨간불로 만든다.
        val seeded =
            listOf(
                "kream.co.kr",
                "coupang.com",
                "naver.com",
                "naver.me",
                "oliveyoung.co.kr",
                "oy.run",
                "a-bly.com",
            )
        seeded.forEach { domain ->
            assertFalse(policyRepository.findById(domain).orElseThrow().headlessAllowed, "$domain 의 허가가 켜져 있다")
            assertFalse(routingPolicy.headlessAllowedOf(ProductLink.parse("https://www.$domain/p/1")))
        }
    }

    @Test
    fun `정책 행이 없는 도메인은 헤드리스 불가이고, 허가를 켠 행만 가능으로 바뀐다`() {
        // "행 없음 = 기본 = 불가" 규약과 그 반대 축(허가를 켜면 즉시 반영)을 한 자리에서 고정한다.
        val domain = "permission-${UUID.randomUUID()}.example.com"
        val link = ProductLink.parse("https://shop.$domain/p/1")
        try {
            assertFalse(routingPolicy.headlessAllowedOf(link), "정책 행이 없는 도메인은 허가 없음이어야 한다")

            // 허가 없이 정책만 있는 행도 여전히 불가 — 행의 존재가 허가를 뜻하지 않는다.
            policyRepository.save(
                ExtractionPlatformPolicyEntity(domain = domain, route = ExtractionRoute.SUPPORTED.name, reason = null),
            )
            routingPolicy.reload()
            assertFalse(routingPolicy.headlessAllowedOf(link))

            policyRepository.save(
                ExtractionPlatformPolicyEntity(
                    domain = domain,
                    route = ExtractionRoute.HEADLESS_FIRST.name,
                    reason = null,
                    headlessAllowed = true,
                    permissionRef = "test permission",
                ),
            )
            routingPolicy.reload()
            assertTrue(routingPolicy.headlessAllowedOf(link), "허가를 켠 행은 서브도메인까지 허가로 판정돼야 한다")
            assertEquals(ExtractionRoute.HEADLESS_FIRST, routingPolicy.routeOf(link))
        } finally {
            policyRepository.findById(domain).ifPresent { policyRepository.delete(it) }
            routingPolicy.reload()
        }
    }

    @Test
    fun `부모 도메인과 서브도메인 정책이 겹치면 더 구체적인(긴) 도메인의 정책이 이긴다`() {
        // 최장 매치가 없으면 승자가 enum 선언 순서로 정해져, 서브도메인만 열어 주는 운영 시나리오
        // (부모 차단 유지 + 서브도메인 직행)가 조용히 무시된다.
        val parent = "overlap-${UUID.randomUUID()}.example.com"
        val sub = "m.$parent"
        try {
            policyRepository.save(ExtractionPlatformPolicyEntity(domain = parent, route = ExtractionRoute.UNSUPPORTED.name, reason = null))
            // HEADLESS_FIRST 는 허가 없이는 만들 수 없다(엔티티 불변식) — 서브도메인만 열어 주는 시나리오라 허가도 함께 켠다.
            policyRepository.save(
                ExtractionPlatformPolicyEntity(
                    domain = sub,
                    route = ExtractionRoute.HEADLESS_FIRST.name,
                    reason = null,
                    headlessAllowed = true,
                    permissionRef = "test permission",
                ),
            )
            routingPolicy.reload()

            assertEquals(ExtractionRoute.HEADLESS_FIRST, routingPolicy.routeOf(ProductLink.parse("https://$sub/p/1")))
            assertEquals(ExtractionRoute.UNSUPPORTED, routingPolicy.routeOf(ProductLink.parse("https://$parent/p/1")))
            assertEquals(ExtractionRoute.UNSUPPORTED, routingPolicy.routeOf(ProductLink.parse("https://www.$parent/p/1")))
        } finally {
            policyRepository.findById(parent).ifPresent { policyRepository.delete(it) }
            policyRepository.findById(sub).ifPresent { policyRepository.delete(it) }
            routingPolicy.reload()
        }
    }

    @Test
    fun `UNSUPPORTED 정책을 추가하면 등록이 400 으로 거부되고, 삭제하면 같은 URL 이 다시 통과한다`() {
        val mockMvc = mockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        // 도메인은 테스트 격리용 유니크 값 — 서브도메인(shop.)까지 매칭되는지 함께 본다.
        val domain = "blocked-${UUID.randomUUID()}.example.com"
        val body = """{"url": "https://shop.$domain/p/1"}"""
        stubProductLinkExtractor.build = { ProductSnapshot(link = it, name = "테스트 상품", price = 9_900) }
        try {
            // 정책 추가 + reload — 배포 없이 곧바로 등록이 거부된다(백오피스 저장 → afterCommit reload 와 같은 경로).
            policyRepository.save(ExtractionPlatformPolicyEntity(domain = domain, route = ExtractionRoute.UNSUPPORTED.name, reason = "테스트"))
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
    fun `SUPPORTED 정책은 라우팅을 바꾸지 않아 등록이 그대로 통과한다`() {
        // SUPPORTED 는 판정이 아니라 기록용 값이다("실측으로 잘 됨을 확인"). 소비처가 UNSUPPORTED·HEADLESS_FIRST 와의
        // 정확한 값 비교만 하므로 기본 체인을 타야 하는데, 누군가 verifyRegistrable 을 "정책 행이 있기만 하면 거절"로
        // 고치면 SUPPORTED 도메인의 등록이 조용히 막힌다 — 그 회귀를 여기서 잡는다.
        val mockMvc = mockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val domain = "supported-${UUID.randomUUID()}.example.com"
        stubProductLinkExtractor.build = { ProductSnapshot(link = it, name = "테스트 상품", price = 9_900) }
        try {
            policyRepository.save(ExtractionPlatformPolicyEntity(domain = domain, route = ExtractionRoute.SUPPORTED.name, reason = "실측 확인"))
            routingPolicy.reload()

            assertEquals(ExtractionRoute.SUPPORTED, routingPolicy.routeOf(ProductLink.parse("https://$domain/p/1")))

            mockMvc
                .perform(
                    post("/api/v1/wishlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                        .content("""{"url": "https://$domain/p/1"}"""),
                ).andExpect(status().isCreated)
        } finally {
            policyRepository.findById(domain).ifPresent { policyRepository.delete(it) }
            routingPolicy.reload()
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
