package com.depromeet.piki.auth.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.auth.infrastructure.redis.RefreshTokenStore
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.uuidToBytes
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// 일반 통합 테스트와 달리 @Transactional 을 사용하지 않는다 — 별도 트랜잭션 동시 진행이
// race 시뮬레이션의 본질이다. 데이터 격리는 매 테스트가 새 게스트 생성으로 보장한다.
//
// refresh 회전의 동시성 계약: 같은 옛 토큰으로 동시에 들어온 요청은 한쪽만 회전하고 나머지는 grace
// replay 로 "같은 새 토큰"에 수렴해야 한다. 과거엔 한쪽 401 + family invalidation 으로 로그아웃됐다(#race).
class RefreshTokenRotationConcurrencyIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Autowired
    private lateinit var refreshTokenStore: RefreshTokenStore

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `같은 refreshToken 으로 동시 요청이 들어오면 모두 200 + 같은 새 토큰으로 수렴한다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()

        val guestResult =
            mockMvc
                .perform(post("/api/v1/auth/guest").contentType(MediaType.APPLICATION_JSON).header("X-Client-Type", "app"))
                .andReturn()
        val guestJson = objectMapper.readTree(guestResult.response.contentAsString)
        val accessToken = guestJson.at("/data/accessToken").asText()
        val r1 = guestJson.at("/data/refreshToken").asText()
        val userId = jwtProvider.parseAccessToken(accessToken)?.userId ?: error("accessToken 파싱 실패")

        try {
            val body = objectMapper.writeValueAsString(mapOf("refreshToken" to r1))

            // 2 단계 래치로 동시 출발 강제 — 한 단계 래치만 쓰면 worker 가 await 도달 전에
            // main 이 countDown 할 수 있어 사실상 순차 실행이 돼 race 재현 신뢰도가 떨어진다.
            val executor = Executors.newFixedThreadPool(2)
            val results =
                try {
                    val workersReady = CountDownLatch(2)
                    val start = CountDownLatch(1)
                    val futures =
                        (0..1).map {
                            executor.submit<Pair<Int, String>> {
                                workersReady.countDown()
                                start.await()
                                val response =
                                    mockMvc
                                        .perform(
                                            post("/api/v1/auth/token/refresh")
                                                .header("X-Client-Type", "app")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(body),
                                        ).andReturn()
                                        .response
                                val refreshed =
                                    response.contentAsString
                                        .takeIf { it.isNotBlank() }
                                        ?.let { objectMapper.readTree(it).at("/data/refreshToken").asText() }
                                        .orEmpty()
                                response.status to refreshed
                            }
                        }
                    // await 에 타임아웃을 둬 worker 가 안 뜨면 무기한 대기하지 않게 한다.
                    assertTrue(workersReady.await(5, TimeUnit.SECONDS), "worker 준비 대기 timeout")
                    start.countDown()
                    futures.map { it.get(10, TimeUnit.SECONDS) }
                } finally {
                    // 예외·타임아웃에도 스레드풀이 반드시 정리되게 한다 (CI hang·스레드 누수 방지).
                    executor.shutdownNow()
                }

            // 두 요청 모두 200 (401·로그아웃 없음)
            assertEquals(setOf(200), results.map { it.first }.toSet())
            // 발급된 refresh 토큰이 동일 토큰으로 수렴 (한쪽 회전, 나머지 grace replay)
            val refreshed = results.map { it.second }.toSet()
            assertEquals(1, refreshed.size, "동시 요청이 서로 다른 토큰을 받으면 수렴 실패: $refreshed")
            // 회전이 실제로 일어나 옛 토큰과는 다르다
            assertNotEquals(r1, refreshed.first())
            assertTrue(refreshed.first().isNotBlank())
        } finally {
            // 세션 id 를 모르는 정리 코드라 전 세션을 지운다(#893 으로 키가 세션별로 갈렸다).
            refreshTokenStore.deleteAll(userId)
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(userId))
        }
    }
}
