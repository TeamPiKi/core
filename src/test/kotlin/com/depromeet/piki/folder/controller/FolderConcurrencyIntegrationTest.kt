package com.depromeet.piki.folder.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.deleteWishDataOf
import com.depromeet.piki.support.runningQueries
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.repository.UserJpaRepository
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.repository.WishJpaRepository
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 한 사용자의 두 기기가 같은 폴더를 동시에 건드리는 경우. 별도 트랜잭션의 동시 진행이 본질이라
// @Transactional 없이 새 UUID 로 격리하고 finally 에서 정리한다
class FolderConcurrencyIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var userJpaRepository: UserJpaRepository

    @Autowired private lateinit var wishJpaRepository: WishJpaRepository

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun member(): UUID =
        UUID.randomUUID().also {
            userJpaRepository.save(User(id = it, nickname = "folderrace", profileImage = "https://cdn.example.com/o.jpg", identityType = IdentityType.MEMBER))
        }

    private fun wishOf(userId: UUID): Long = wishJpaRepository.save(Wish(userId = userId, itemId = null)).getId()

    private fun MockHttpServletRequestBuilder.by(userId: UUID): MockHttpServletRequestBuilder =
        header(HttpHeaders.AUTHORIZATION, "Bearer ${jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)}")

    private fun MockHttpServletRequestBuilder.json(body: Any): MockHttpServletRequestBuilder =
        contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))

    private fun createFolder(
        userId: UUID,
        name: String,
    ): Long {
        val response = mockMvc().perform(post("/api/v1/folders").by(userId).json(mapOf("name" to name))).andReturn().response
        assertEquals(201, response.status)
        return objectMapper.readTree(response.contentAsString).path("data").path("id").asLong()
    }

    private fun put(
        userId: UUID,
        folderId: Long,
        vararg wishIds: Long,
    ) {
        val status = mockMvc().perform(post("/api/v1/folders/$folderId/wishes").by(userId).json(mapOf("wishIds" to wishIds.toList()))).andReturn().response.status
        assertEquals(200, status)
    }

    // 두 기기 요청을 동시에 출발시키고 각 응답 status 를 요청 순서대로 돌려준다
    private fun race(vararg requests: () -> MockHttpServletRequestBuilder): List<Int> {
        val statuses = IntArray(requests.size)
        val executor = Executors.newFixedThreadPool(requests.size)
        val ready = CountDownLatch(requests.size)
        val start = CountDownLatch(1)
        val done = CountDownLatch(requests.size)
        // 단언 실패에도 executor 를 종료해야 non-daemon 스레드가 테스트 JVM 을 붙잡지 않는다
        try {
            requests.forEachIndexed { index, request ->
                executor.submit {
                    ready.countDown()
                    start.await()
                    try {
                        statuses[index] = mockMvc().perform(request()).andReturn().response.status
                    } finally {
                        done.countDown()
                    }
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "작업 스레드가 10초 안에 시작되어야 한다")
            start.countDown()
            assertTrue(done.await(15, TimeUnit.SECONDS), "동시 요청이 15초 안에 끝나야 한다 (서로 기다리며 멈추면 실패)")
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
        return statuses.toList()
    }

    private fun linkCount(
        folderId: Long,
        wishId: Long? = null,
    ): Int {
        wishId ?: return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM folder_wishes WHERE folder_id = ?", Int::class.java, folderId) ?: 0
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM folder_wishes WHERE folder_id = ? AND wish_id = ?", Int::class.java, folderId, wishId) ?: 0
    }

    private fun cleanUp(userId: UUID) {
        val userBytes = uuidToBytes(userId)
        jdbcTemplate.update("DELETE fw FROM folder_wishes fw JOIN folders f ON f.id = fw.folder_id WHERE f.user_id = ?", userBytes)
        jdbcTemplate.update("DELETE FROM folders WHERE user_id = ?", userBytes)
        jdbcTemplate.deleteWishDataOf(userId)
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userBytes)
    }

    @Test
    fun `두 기기가 같은 위시를 같은 폴더에 동시에 넣어도 둘 다 성공하고 연결은 하나다`() {
        // 폴더 행 잠금이 없으면 둘 다 "아직 없음" 을 읽고 넣어 유니크 키에 부딪혀 한쪽이 500
        val userId = member()
        try {
            val folderId = createFolder(userId, "가방")
            val wishId = wishOf(userId)

            val statuses =
                race(
                    { post("/api/v1/folders/$folderId/wishes").by(userId).json(mapOf("wishIds" to listOf(wishId))) },
                    { post("/api/v1/folders/$folderId/wishes").by(userId).json(mapOf("wishIds" to listOf(wishId))) },
                )

            assertEquals(listOf(200, 200), statuses)
            assertEquals(1, linkCount(folderId, wishId))
        } finally {
            cleanUp(userId)
        }
    }

    @Test
    fun `두 기기가 두 폴더 사이에서 반대 방향으로 동시에 옮겨도 멈추지 않고 위시마다 한 폴더에만 남는다`() {
        // 두 폴더를 요청 순서대로 잠그면 A 는 가방 다음 선물, B 는 선물 다음 가방을 기다리며 교착에 빠진다
        val userId = member()
        try {
            val bag = createFolder(userId, "가방")
            val gift = createFolder(userId, "선물")
            val a = wishOf(userId)
            val b = wishOf(userId)
            put(userId, bag, a)
            put(userId, gift, b)

            val statuses =
                race(
                    { post("/api/v1/folders/$bag/wishes/move").by(userId).json(mapOf("wishIds" to listOf(a), "targetFolderId" to gift)) },
                    { post("/api/v1/folders/$gift/wishes/move").by(userId).json(mapOf("wishIds" to listOf(b), "targetFolderId" to bag)) },
                )

            assertEquals(listOf(200, 200), statuses)
            assertEquals(listOf(0, 1), listOf(linkCount(bag, a), linkCount(gift, a)), "a 는 선물에만")
            assertEquals(listOf(1, 0), listOf(linkCount(bag, b), linkCount(gift, b)), "b 는 가방에만")
        } finally {
            cleanUp(userId)
        }
    }

    @Test
    fun `한 기기가 폴더를 지우는 동안 다른 기기가 넣어도 지운 폴더에 연결이 남지 않는다`() {
        // 넣기가 먼저면 지우기가 그 연결까지 지우고, 지우기가 먼저면 넣기가 404 를 받는다. 어느 순서든 고아 연결은 없다
        val userId = member()
        try {
            val folderId = createFolder(userId, "가방")
            val wishId = wishOf(userId)

            val statuses =
                race(
                    { delete("/api/v1/folders/$folderId").by(userId) },
                    { post("/api/v1/folders/$folderId/wishes").by(userId).json(mapOf("wishIds" to listOf(wishId))) },
                )

            assertEquals(200, statuses[0])
            assertTrue(statuses[1] in setOf(200, 404), "넣기는 지우기보다 먼저면 200, 나중이면 404: ${statuses[1]}")
            assertEquals(0, linkCount(folderId))
        } finally {
            cleanUp(userId)
        }
    }

    @Test
    fun `탈퇴가 회원 행을 잡은 동안 다른 기기의 폴더 만들기는 기다렸다가 탈퇴한 계정으로 거절된다`() {
        // 두 요청을 그냥 동시에 보내면 겹치는 순간이 짧아 잠금이 없어도 통과한다(실측). 그래서 탈퇴 트랜잭션을 흉내 내
        // 회원 행을 잡아 둔 채, 만들기가 그 잠금을 실제로 기다리는지 확인한 뒤 놓는다.
        // 잠금이 없으면 만들기는 커밋 전 활성 상태를 읽고 201 로 끝나 탈퇴한 회원의 폴더가 남는다
        val userId = member()
        val withdrawer = Executors.newSingleThreadExecutor()
        try {
            val locked = CountDownLatch(1)
            val withdrawing =
                withdrawer.submit {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        jdbcTemplate.queryForObject("SELECT nickname FROM users WHERE id = ? FOR UPDATE", String::class.java, uuidToBytes(userId))
                        jdbcTemplate.update("UPDATE users SET deleted_at = NOW(6) WHERE id = ?", uuidToBytes(userId))
                        locked.countDown()
                        await().atMost(Duration.ofSeconds(5)).until { jdbcTemplate.runningQueries("%from users %for update%") > 0 }
                    }
                }
            assertTrue(locked.await(5, TimeUnit.SECONDS))

            val status = mockMvc().perform(post("/api/v1/folders").by(userId).json(mapOf("name" to "가방"))).andReturn().response.status

            withdrawing.get(10, TimeUnit.SECONDS)
            assertEquals(409, status, "회원 행 잠금을 기다린 뒤 탈퇴 상태를 읽어야 한다")
            val folders = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM folders WHERE user_id = ?", Int::class.java, uuidToBytes(userId))
            assertEquals(0, folders)
        } finally {
            withdrawer.shutdownNow()
            withdrawer.awaitTermination(5, TimeUnit.SECONDS)
            cleanUp(userId)
        }
    }
}
