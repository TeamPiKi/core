package com.depromeet.piki.user.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubImageStorage
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.service.WithdrawalService
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Transactional
class WithdrawalIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Autowired
    private lateinit var stubImageStorage: StubImageStorage

    @Autowired
    private lateinit var withdrawalService: WithdrawalService

    @Autowired
    private lateinit var stubWithdrawnTokenStore: com.depromeet.piki.support.StubWithdrawnTokenStore

    @Autowired
    private lateinit var stubRefreshTokenStore: com.depromeet.piki.support.StubRefreshTokenStore

    @Autowired
    private lateinit var meterRegistry: io.micrometer.core.instrument.MeterRegistry

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun token(
        userId: UUID,
        identityType: IdentityType,
    ): String = jwtProvider.generateAccessToken(userId, identityType)

    private fun insertUser(
        userId: UUID,
        nickname: String,
        identityType: IdentityType,
    ) {
        jdbcTemplate.update(
            "INSERT INTO users (id, nickname, profile_image, identity_type, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
            uuidToBytes(userId),
            nickname,
            "https://example.com/img.png",
            identityType.name,
        )
    }

    private fun insertUserDetail(userId: UUID) {
        jdbcTemplate.update(
            "INSERT INTO user_details (user_id, provider, social_id, created_at, updated_at) " +
                "VALUES (?, ?, ?, NOW(6), NOW(6))",
            uuidToBytes(userId),
            "KAKAO",
            "social_${userId.toString().take(8)}",
        )
    }

    private fun insertUserDevice(userId: UUID) {
        jdbcTemplate.update(
            "INSERT INTO user_devices (user_id, device_id, fcm_token, created_at, updated_at) " +
                "VALUES (?, ?, ?, NOW(6), NOW(6))",
            uuidToBytes(userId),
            "device_${userId.toString().take(8)}",
            "fcm_${userId.toString().take(8)}",
        )
    }

    // wish 는 활성 snapshot 을 가리킨다(snapshot_id NOT NULL). 먼저 item_snapshots 행을 만들고 그 id 를 snapshot_id 로 넣는다.
    private fun insertWish(userId: UUID): Long {
        val snapshotId = insertItemSnapshot(itemId = 1L)
        jdbcTemplate.update(
            "INSERT INTO wishes (user_id, snapshot_id, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
            uuidToBytes(userId),
            snapshotId,
        )
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long::class.java)!!
    }

    // 알림·역조회 무관한 fixture 라 PROCESSING 상태로 최소 행만 만든다. status·item_id·timestamp 가 NOT NULL.
    private fun insertItemSnapshot(itemId: Long): Long {
        jdbcTemplate.update(
            "INSERT INTO item_snapshots (item_id, status, created_at, updated_at) VALUES (?, 'PROCESSING', NOW(6), NOW(6))",
            itemId,
        )
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long::class.java)!!
    }

    private fun insertNotification(userId: UUID): Long {
        jdbcTemplate.update(
            "INSERT INTO notifications (user_id, type, title, body, ref_id, is_read, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, FALSE, NOW(6), NOW(6))",
            uuidToBytes(userId),
            "TOURNAMENT_JOINED",
            "제목",
            "본문",
            1L,
        )
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long::class.java)!!
    }

    @Test
    fun `DELETE users me - MEMBER 가 탈퇴하면 200 과 data null 이 반환된다`() {
        val userId = UUID.randomUUID()
        insertUser(userId, "멤버닉네임", IdentityType.MEMBER)

        mockMvc()
            .perform(
                delete("/api/v1/users/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data").value(null))
            .andExpect(jsonPath("$.detail").value("완료했어요."))
    }

    @Test
    fun `DELETE users me - 탈퇴 후 같은 access token 으로 접근하면 401 이 반환된다`() {
        val userId = UUID.randomUUID()
        insertUser(userId, "멤버닉네임", IdentityType.MEMBER)
        val accessToken = token(userId, IdentityType.MEMBER)

        // 탈퇴 — access token denylist 에 마킹된다.
        mockMvc()
            .perform(
                delete("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"),
            ).andExpect(status().isOk)

        // 탈퇴 직후, 아직 만료 안 된 같은 access token 으로 인증 엔드포인트 호출 → denylist 로 거부(401).
        mockMvc()
            .perform(
                get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"),
            ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `DELETE users me - 인증 헤더 없으면 401 이 반환된다`() {
        mockMvc()
            .perform(delete("/api/v1/users/me"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `DELETE users me - 게스트가 호출하면 403 이 반환된다`() {
        val userId = UUID.randomUUID()
        insertUser(userId, "게스트닉네임", IdentityType.GUEST)

        mockMvc()
            .perform(
                delete("/api/v1/users/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.GUEST)}"),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value("게스트는 탈퇴할 수 없어요."))
            .andExpect(jsonPath("$.data").value(null))
    }

    @Test
    fun `DELETE users me - cascade 로 PII 와 콘텐츠가 즉시 하드삭제되고 닉네임이 익명화된다`() {
        val userId = UUID.randomUUID()
        insertUser(userId, "멤버닉네임", IdentityType.MEMBER)
        insertUserDetail(userId)
        insertUserDevice(userId)
        val wishId = insertWish(userId)
        val notificationId = insertNotification(userId)

        mockMvc()
            .perform(
                delete("/api/v1/users/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}"),
            ).andExpect(status().isOk)

        // cascade(엔티티 remove + tombstone UPDATE)는 테스트 트랜잭션 세션에 버퍼만 된 상태다.
        // jdbcTemplate raw SELECT 는 세션을 경유하지 않으므로, flush 로 DB 에 먼저 내린다.
        entityManager.flush()
        entityManager.clear()

        // user_details 하드삭제
        val detailCount =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_details WHERE user_id = ?",
                Long::class.java,
                uuidToBytes(userId),
            )
        assertEquals(0L, detailCount)

        // user_devices 하드삭제
        val deviceCount =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_devices WHERE user_id = ?",
                Long::class.java,
                uuidToBytes(userId),
            )
        assertEquals(0L, deviceCount)

        // wishes 하드삭제 (행 자체가 사라짐)
        val wishCount =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wishes WHERE id = ?",
                Long::class.java,
                wishId,
            )
        assertEquals(0L, wishCount, "wish 가 하드삭제되어야 한다")

        // notifications 하드삭제
        val notiCount =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE id = ?",
                Long::class.java,
                notificationId,
            )
        assertEquals(0L, notiCount, "notification 이 하드삭제되어야 한다")

        // users tombstone — 행은 남고 닉네임이 익명화됨
        val nickname =
            jdbcTemplate.queryForObject(
                "SELECT nickname FROM users WHERE id = ?",
                String::class.java,
                uuidToBytes(userId),
            )
        assertNotEquals("멤버닉네임", nickname)
        assertEquals("탈퇴" + userId.toString().replace("-", "").take(8), nickname)
        val userDeletedAt =
            jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM users WHERE id = ?",
                java.sql.Timestamp::class.java,
                uuidToBytes(userId),
            )
        assertNotNull(userDeletedAt, "user 가 tombstone 으로 soft-delete 되어야 한다")
    }

    @Test
    fun `DELETE users me - 공유 토너먼트 출전 아이템은 보존된다`() {
        val userId = UUID.randomUUID()
        insertUser(userId, "멤버닉네임", IdentityType.MEMBER)
        // 이 유저가 출전시킨 tournament_item 1건 (공유 토너먼트 자산). 출전 시점 고정 snapshot 을 먼저 만들고 그 id 를 snapshot_id 로 넣는다.
        val snapshotId = insertItemSnapshot(itemId = 1L)
        jdbcTemplate.update(
            "INSERT INTO tournament_items (tournament_id, snapshot_id, user_id, created_at, updated_at) " +
                "VALUES (?, ?, ?, NOW(6), NOW(6))",
            999L,
            snapshotId,
            uuidToBytes(userId),
        )

        mockMvc()
            .perform(
                delete("/api/v1/users/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}"),
            ).andExpect(status().isOk)

        // tournament_items 는 절대 건드리지 않는다 — 행이 그대로 남아야 한다(userId 는 tombstone 으로 "탈퇴회원" 으로 풀림).
        val tiCount =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tournament_items WHERE user_id = ? AND deleted_at IS NULL",
                Long::class.java,
                uuidToBytes(userId),
            )
        assertEquals(1L, tiCount)
    }

    @Test
    fun `DELETE users me - 탈퇴 시 S3 프로필 prefix 가 삭제 호출된다`() {
        val userId = UUID.randomUUID()
        insertUser(userId, "멤버닉네임", IdentityType.MEMBER)

        mockMvc()
            .perform(
                delete("/api/v1/users/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}"),
            ).andExpect(status().isOk)

        // 탈퇴 시 유저 프로필 이미지(얼굴 등 PII)를 S3 에서 prefix 통째로 파기한다.
        assertContains(stubImageStorage.deletedPrefixes, "profiles/$userId/")
    }

    @Test
    fun `withdraw 를 두 번 호출해도 멱등하게 tombstone 이 유지된다`() {
        // HTTP 로는 첫 탈퇴 후 그 토큰이 denylist 로 막혀(401) 두 번째 호출이 서비스까지 못 닿으므로,
        // 서비스 멱등성(이미 tombstone 이어도 안전)은 withdrawalService 를 직접 두 번 호출해 검증한다.
        val userId = UUID.randomUUID()
        insertUser(userId, "멤버닉네임", IdentityType.MEMBER)

        withdrawalService.withdraw(userId)
        withdrawalService.withdraw(userId) // 두 번째도 예외 없이 멱등 통과

        // tombstone UPDATE 가 세션에 버퍼만 된 상태이므로 jdbcTemplate 검증 전 flush 한다.
        entityManager.flush()
        entityManager.clear()

        val nickname =
            jdbcTemplate.queryForObject(
                "SELECT nickname FROM users WHERE id = ?",
                String::class.java,
                uuidToBytes(userId),
            )
        assertEquals("탈퇴" + userId.toString().replace("-", "").take(8), nickname)
    }

    @Test
    fun `markWithdrawn 이 실패해도 탈퇴는 200 으로 완료되고 실패 메트릭이 오른다`() {
        val userId = UUID.randomUUID()
        insertUser(userId, "멤버닉네임", IdentityType.MEMBER)
        // Redis 장애로 access denylist 마킹이 던지는 상황.
        stubWithdrawnTokenStore.behavior = { throw RuntimeException("redis down") }
        val before =
            meterRegistry
                .counter(
                    com.depromeet.piki.user.service.WithdrawalMetrics.METRIC,
                    com.depromeet.piki.user.service.WithdrawalMetrics.TAG_STEP,
                    com.depromeet.piki.user.service.WithdrawalMetrics.STEP_MARK_WITHDRAWN,
                ).count()

        try {
            mockMvc()
                .perform(
                    delete("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}"),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.detail").value("완료했어요."))
        } finally {
            stubWithdrawnTokenStore.reset()
        }

        // DB 는 정상 탈퇴(tombstone)돼야 한다 — 무효화 실패가 탈퇴를 되돌리지 않는다.
        entityManager.flush()
        entityManager.clear()
        val deletedAt =
            jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM users WHERE id = ?",
                java.sql.Timestamp::class.java,
                uuidToBytes(userId),
            )
        assertNotNull(deletedAt)
        // 최종 실패가 메트릭으로 관측돼야 한다.
        val after =
            meterRegistry
                .counter(
                    com.depromeet.piki.user.service.WithdrawalMetrics.METRIC,
                    com.depromeet.piki.user.service.WithdrawalMetrics.TAG_STEP,
                    com.depromeet.piki.user.service.WithdrawalMetrics.STEP_MARK_WITHDRAWN,
                ).count()
        assertEquals(before + 1.0, after)
    }

    @Test
    fun `refresh 토큰 삭제가 실패해도 탈퇴는 200 이고 markWithdrawn 은 정상 실행된다`() {
        val userId = UUID.randomUUID()
        insertUser(userId, "멤버닉네임", IdentityType.MEMBER)
        // refresh 삭제만 던지고, 이후 단계(markWithdrawn)는 정상이어야 한다 — best-effort 격리.
        stubRefreshTokenStore.onDelete = { throw RuntimeException("redis down") }

        try {
            mockMvc()
                .perform(
                    delete("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}"),
                ).andExpect(status().isOk)
        } finally {
            stubRefreshTokenStore.reset()
        }

        // refresh 실패가 markWithdrawn 을 막지 않았어야 한다 — denylist 에 마킹돼 있다.
        assertTrue(stubWithdrawnTokenStore.isWithdrawn(userId))
    }

    @Test
    fun `markWithdrawn 이 1회 실패해도 즉시 재시도로 성공하면 메트릭이 오르지 않는다`() {
        val userId = UUID.randomUUID()
        insertUser(userId, "멤버닉네임", IdentityType.MEMBER)
        // 1회째만 던지고 2회째는 실제 저장(순간 blip 재현).
        var calls = 0
        stubWithdrawnTokenStore.behavior = { id ->
            calls++
            if (calls == 1) throw RuntimeException("redis blip") else stubWithdrawnTokenStore.defaultBehavior(id)
        }
        val before =
            meterRegistry
                .counter(
                    com.depromeet.piki.user.service.WithdrawalMetrics.METRIC,
                    com.depromeet.piki.user.service.WithdrawalMetrics.TAG_STEP,
                    com.depromeet.piki.user.service.WithdrawalMetrics.STEP_MARK_WITHDRAWN,
                ).count()

        try {
            mockMvc()
                .perform(
                    delete("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}"),
                ).andExpect(status().isOk)

            // 2회 호출(최초 + 재시도 1회)로 끝나야 한다. reset() 이 markWithdrawnInvocations 도 비우므로 finally 전에 단언한다.
            assertEquals(2, stubWithdrawnTokenStore.markWithdrawnInvocations.count { it == userId })
            // 재시도가 성공했으니 denylist 에 마킹돼 있고, 실패 메트릭은 오르지 않았어야 한다.
            assertTrue(stubWithdrawnTokenStore.isWithdrawn(userId))
        } finally {
            stubWithdrawnTokenStore.reset()
        }

        val after =
            meterRegistry
                .counter(
                    com.depromeet.piki.user.service.WithdrawalMetrics.METRIC,
                    com.depromeet.piki.user.service.WithdrawalMetrics.TAG_STEP,
                    com.depromeet.piki.user.service.WithdrawalMetrics.STEP_MARK_WITHDRAWN,
                ).count()
        assertEquals(before, after)
    }
}
