package com.depromeet.piki.auth.controller

import com.depromeet.piki.auth.infrastructure.oauth.apple.AppleNotificationEvent
import com.depromeet.piki.auth.infrastructure.oauth.apple.AppleNotificationEventType
import com.depromeet.piki.auth.infrastructure.oauth.apple.AppleNotificationException
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubAppleNotificationVerifier
import com.depromeet.piki.support.StubRefreshTokenStore
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Transactional
class AppleNotificationControllerIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var stubAppleNotificationVerifier: StubAppleNotificationVerifier

    @Autowired
    private lateinit var stubRefreshTokenStore: StubRefreshTokenStore

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    // Apple 로 가입한 MEMBER 1명 생성 (users + user_details provider=APPLE). socialId 가 알림의 sub 와 매칭된다.
    private fun insertAppleUser(
        userId: UUID,
        socialId: String,
        nickname: String = "애플유저",
    ) {
        jdbcTemplate.update(
            "INSERT INTO users (id, nickname, profile_image, identity_type, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
            uuidToBytes(userId),
            nickname,
            "https://example.com/img.png",
            IdentityType.MEMBER.name,
        )
        jdbcTemplate.update(
            "INSERT INTO user_details (user_id, provider, social_id, created_at, updated_at) " +
                "VALUES (?, ?, ?, NOW(6), NOW(6))",
            uuidToBytes(userId),
            "APPLE",
            socialId,
        )
    }

    private fun postNotification(): org.springframework.test.web.servlet.ResultActions =
        mockMvc().perform(
            post("/api/v1/auth/apple/notifications")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("payload", "signed-jwt-payload"),
        )

    @Test
    fun `account-delete 알림이면 해당 유저가 탈퇴(tombstone)되고 200 이 반환된다`() {
        val userId = UUID.randomUUID()
        val socialId = "apple_sub_${userId.toString().take(8)}"
        insertAppleUser(userId, socialId, nickname = "애플멤버")
        stubAppleNotificationVerifier.verifyStub = {
            AppleNotificationEvent(AppleNotificationEventType.ACCOUNT_DELETE, socialId)
        }

        postNotification()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").value(null))
            .andExpect(jsonPath("$.detail").value("완료했어요."))

        entityManager.flush()
        entityManager.clear()

        // users tombstone — 닉네임 익명화 + deleted_at set
        val nickname =
            jdbcTemplate.queryForObject(
                "SELECT nickname FROM users WHERE id = ?",
                String::class.java,
                uuidToBytes(userId),
            )
        assertNotEquals("애플멤버", nickname)
        val deletedAt =
            jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM users WHERE id = ?",
                java.sql.Timestamp::class.java,
                uuidToBytes(userId),
            )
        assertNotNull(deletedAt, "account-delete 면 user 가 tombstone 으로 탈퇴되어야 한다")

        // user_details(PII) 하드삭제
        val detailCount =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_details WHERE user_id = ?",
                Long::class.java,
                uuidToBytes(userId),
            )
        assertEquals(0L, detailCount)
    }

    @Test
    fun `consent-revoked 알림이면 refresh token 만 삭제되고 계정·데이터는 유지된다`() {
        val userId = UUID.randomUUID()
        val socialId = "apple_sub_${userId.toString().take(8)}"
        insertAppleUser(userId, socialId, nickname = "애플멤버")
        stubRefreshTokenStore.save(userId, "session-1", "refresh-token-value")
        stubRefreshTokenStore.save(userId, "session-2", "refresh-token-value-2")
        stubAppleNotificationVerifier.verifyStub = {
            AppleNotificationEvent(AppleNotificationEventType.CONSENT_REVOKED, socialId)
        }

        postNotification()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").value(null))

        entityManager.flush()
        entityManager.clear()

        // refresh token 은 삭제(재발급 차단)
        assertNull(stubRefreshTokenStore.get(userId, "session-1"), "consent-revoked 면 전 세션이 삭제되어야 한다")
        assertNull(stubRefreshTokenStore.get(userId, "session-2"), "consent-revoked 면 전 세션이 삭제되어야 한다")

        // 계정은 살아있음 — tombstone 되지 않고 닉네임·user_detail 유지(재로그인 복귀 보장)
        val nickname =
            jdbcTemplate.queryForObject(
                "SELECT nickname FROM users WHERE id = ?",
                String::class.java,
                uuidToBytes(userId),
            )
        assertEquals("애플멤버", nickname)
        val deletedAt =
            jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM users WHERE id = ?",
                java.sql.Timestamp::class.java,
                uuidToBytes(userId),
            )
        assertNull(deletedAt, "consent-revoked 면 계정은 유지되어야 한다")
        val detailCount =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_details WHERE user_id = ?",
                Long::class.java,
                uuidToBytes(userId),
            )
        assertEquals(1L, detailCount, "consent-revoked 면 user_detail 이 유지되어야 한다")
    }

    @Test
    fun `대상 유저가 없으면 멱등하게 200 으로 흡수한다`() {
        // 매칭되는 user_detail 이 없는 sub (미가입 또는 이미 탈퇴로 파기됨)
        stubAppleNotificationVerifier.verifyStub = {
            AppleNotificationEvent(AppleNotificationEventType.ACCOUNT_DELETE, "not_exist_sub")
        }

        postNotification()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").value(null))
    }

    @Test
    fun `서명 검증에 실패하면 401 이 반환된다`() {
        stubAppleNotificationVerifier.verifyStub = { throw AppleNotificationException.invalidSignature() }

        postNotification()
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.detail").value("유효하지 않은 Apple 서버 알림입니다."))
            .andExpect(jsonPath("$.data").value(null))
    }

    @ParameterizedTest
    @EnumSource(
        value = AppleNotificationEventType::class,
        names = ["EMAIL_DISABLED", "EMAIL_ENABLED", "UNKNOWN"],
    )
    fun `상태 변경 없는 이벤트는 계정을 건드리지 않고 200 으로 흡수한다`(type: AppleNotificationEventType) {
        // no-op 계열(email 릴레이 on·off, 미지원 타입). UNKNOWN→200 계약이 깨지면 Apple 재시도로 운영 부담이 커지므로 함께 고정한다.
        val userId = UUID.randomUUID()
        val socialId = "apple_sub_${userId.toString().take(8)}"
        insertAppleUser(userId, socialId, nickname = "애플멤버")
        stubAppleNotificationVerifier.verifyStub = { AppleNotificationEvent(type, socialId) }

        postNotification().andExpect(status().isOk)

        entityManager.flush()
        entityManager.clear()

        val deletedAt =
            jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM users WHERE id = ?",
                java.sql.Timestamp::class.java,
                uuidToBytes(userId),
            )
        assertNull(deletedAt, "$type 이벤트는 계정 상태를 바꾸지 않는다")
    }
}
