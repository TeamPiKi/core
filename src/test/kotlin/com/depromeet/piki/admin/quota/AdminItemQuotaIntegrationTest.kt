package com.depromeet.piki.admin.quota

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.common.ratelimit.DbItemQuotaSettings
import com.depromeet.piki.common.ratelimit.ItemQuotaSettingsJpaRepository
import com.depromeet.piki.common.ratelimit.RedisItemQuotaStore
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

// 백오피스 한도 조절 contract (#934). 이 기능의 전부는 **"화면에서 바꾼 값이 배포 없이 실제 판정에 반영되는가"** 라,
// 저장이 DB 에 남는 것으로 끝내지 않고 그 뒤 등록 요청이 실제로 막히는지까지 본다.
//
// @Transactional 자동 롤백을 쓰지 않는다 — 저장이 afterCommit 에 캐시 reload 를 걸고(롤백되면 타지 않는다),
// 설정 캐시(@Volatile)는 롤백으로 되돌아가지 않아 다른 테스트로 누수된다. 각 테스트가 자기 뒷정리를 하고 reload 한다
// (AdminExtractionModelIntegrationTest 와 같은 이유). 설정 행은 서비스에 하나뿐이라 UUID 로 격리할 수 없다.
class AdminItemQuotaIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var repository: ItemQuotaSettingsJpaRepository

    @Autowired
    private lateinit var settings: DbItemQuotaSettings

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Test
    fun `계정 한도를 내리면 배포 없이 그 다음 등록부터 막힌다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)

        try {
            // 한도를 1 로 내린다. 화면 폼과 같은 경로(POST)로 저장해 afterCommit reload 까지 태운다.
            saveQuota(mockMvc, userLimit = 1)

            // 첫 건은 통과해 몫을 채우고,
            register(mockMvc, userId, "https://www.musinsa.com/products/101").andExpect(status().isCreated)
            // 두 번째는 방금 내린 한도에 걸린다 — 재시작 없이 값이 먹었다는 뜻이다.
            register(mockMvc, userId, "https://www.musinsa.com/products/102").andExpect(status().isTooManyRequests)
        } finally {
            cleanUp(userId)
        }
    }

    @Test
    fun `전역 상한을 내리면 자기 몫이 남아 있어도 막힌다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)

        try {
            saveQuota(mockMvc, capacityLimit = 1)
            // 전역 카운터를 상한까지 채운다. 이 사용자는 자기 몫을 한 건도 쓰지 않았다.
            redisTemplate.opsForValue().set(RedisItemQuotaStore.CAPACITY_KEY, "1", java.time.Duration.ofMinutes(5))

            register(mockMvc, userId, "https://www.musinsa.com/products/103").andExpect(status().isServiceUnavailable)
        } finally {
            cleanUp(userId)
        }
    }

    @Test
    fun `끄면 한도가 통째로 걸리지 않는다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)

        try {
            // 한도를 1 로 내리고 동시에 끈다 — 스위치가 이겨야 한다. Boolean false 를 "값 없음" 으로 흘리면
            // 이 요청이 429 로 막히고, 정상 사용자를 막고 있는 상태를 되돌릴 수 없게 된다.
            saveQuota(mockMvc, userLimit = 1, enabled = false)

            register(mockMvc, userId, "https://www.musinsa.com/products/104").andExpect(status().isCreated)
            register(mockMvc, userId, "https://www.musinsa.com/products/105").andExpect(status().isCreated)
            // 꺼져 있으면 차감 자체를 건너뛴다.
            assertNull(redisTemplate.opsForValue().get(RedisItemQuotaStore.USER_KEY_PREFIX + userId))
        } finally {
            cleanUp(userId)
        }
    }

    @Test
    fun `되돌리기는 오버라이드 행을 지워 기본값으로 복귀시킨다`() {
        val mockMvc = buildMockMvc()
        val defaultUserLimit = settings.current().userLimit

        try {
            saveQuota(mockMvc, userLimit = 1)
            assertEquals(1, settings.current().userLimit)

            mockMvc
                .perform(post("/admin/item-quota/reset").with(csrf()))
                .andExpect(redirectedUrl("/admin/item-quota?reset"))

            assertEquals(defaultUserLimit, settings.current().userLimit)
            assertNull(repository.findAll().firstOrNull())
        } finally {
            cleanUp(null)
        }
    }

    @Test
    fun `범위를 벗어난 값은 저장되지 않고 화면에 사유가 표시된다`() {
        val mockMvc = buildMockMvc()

        try {
            mockMvc
                .perform(post("/admin/item-quota").with(csrf()).param("userLimit", "0"))
                // 리다이렉트가 아니라 목록을 다시 그린다 — 제출값을 잃지 않기 위해서다.
                .andExpect(status().isOk)
                .andExpect(content().string(org.hamcrest.Matchers.containsString("계정 한도는 1 이상이어야 합니다")))

            assertNull(repository.findAll().firstOrNull())
        } finally {
            cleanUp(null)
        }
    }

    @Test
    fun `계정 사용량 조회는 지금 창의 사용량을 보여준다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        redisTemplate
            .opsForValue()
            .set(RedisItemQuotaStore.USER_KEY_PREFIX + userId, "7", java.time.Duration.ofMinutes(5))

        try {
            mockMvc
                .perform(get("/admin/item-quota/usage").param("userId", userId.toString()))
                .andExpect(status().isOk)
                .andExpect(content().string(org.hamcrest.Matchers.containsString(userId.toString())))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(">7<")))
        } finally {
            cleanUp(userId)
        }
    }

    @Test
    fun `userId 형식이 잘못되면 사유를 화면에 표시한다`() {
        val mockMvc = buildMockMvc()

        mockMvc
            .perform(get("/admin/item-quota/usage").param("userId", "not-a-uuid"))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("userId 형식이 올바르지 않습니다")))
    }

    private fun buildMockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    // 화면 폼과 같은 경로로 저장한다 — 서비스를 직접 부르면 컨트롤러 바인딩과 afterCommit reload 를 건너뛴다.
    private fun saveQuota(
        mockMvc: MockMvc,
        userLimit: Int? = null,
        capacityLimit: Int? = null,
        enabled: Boolean? = null,
    ) {
        val request = post("/admin/item-quota").with(csrf())
        userLimit?.let { request.param("userLimit", it.toString()) }
        capacityLimit?.let { request.param("capacityLimit", it.toString()) }
        enabled?.let { request.param("enabled", it.toString()) }
        mockMvc.perform(request).andExpect(redirectedUrl("/admin/item-quota?updated"))
    }

    private fun register(
        mockMvc: MockMvc,
        userId: UUID,
        url: String,
    ) = mockMvc.perform(
        post("/api/v1/wishlists")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)}")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"url":"$url"}"""),
    )

    private fun insertMember(userId: UUID) {
        jdbcTemplate.update(
            "INSERT INTO users (id, nickname, identity_type, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
            uuidToBytes(userId),
            userId.toString().take(10),
            IdentityType.MEMBER.name,
        )
    }

    // 설정 행·Redis 카운터·유저 행은 롤백 대상이 아니거나(@Transactional 미사용) Redis 라, 직접 되돌린다.
    // 캐시도 함께 reload 해 다음 테스트가 방금 내린 한도를 물려받지 않게 한다.
    private fun cleanUp(userId: UUID?) {
        repository.deleteAll()
        settings.reload()
        redisTemplate.delete(RedisItemQuotaStore.CAPACITY_KEY)
        userId?.let {
            redisTemplate.delete(RedisItemQuotaStore.USER_KEY_PREFIX + it)
            jdbcTemplate.update("DELETE FROM wishes WHERE user_id = ?", uuidToBytes(it))
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(it))
        }
    }
}
