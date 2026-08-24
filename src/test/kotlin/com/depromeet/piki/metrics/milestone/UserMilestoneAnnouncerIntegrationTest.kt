package com.depromeet.piki.metrics.milestone

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.metrics.dashboard.MetricsRepository
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubDiscordMessageSender
import com.depromeet.piki.support.uuidToBytes
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 사용자 수 마일스톤 알림. announcer 는 임계값·채널·문구를 설정에서 읽으므로, 테스트는 명시 설정으로 announcer 를
// 직접 구성해(실제 repo + stub Discord) 오케스트레이션을 검증한다. (Spring 빈 announcer 는 test 설정 임계값이 비어 inert)
@Transactional
class UserMilestoneAnnouncerIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var metricsRepository: MetricsRepository

    @Autowired private lateinit var milestoneRepository: UserMilestoneRepository

    @Autowired private lateinit var stubSender: StubDiscordMessageSender

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    private fun announcer(vararg thresholds: Long) =
        UserMilestoneAnnouncer(
            metricsRepository,
            milestoneRepository,
            stubSender,
            AdminProperties(
                userMilestoneThresholds = thresholds.toList(),
                userMilestoneChannelId = "test-channel",
                userMilestoneMessage = "달성 {threshold}",
            ),
        )

    private fun insertUser(
        identityType: String = "MEMBER",
        withdrawn: Boolean = false,
        developer: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO users (id, nickname, profile_image, identity_type, created_at, updated_at, deleted_at) " +
                "VALUES (?, ?, '', ?, NOW(6), NOW(6), ${if (withdrawn) "NOW(6)" else "NULL"})",
            uuidToBytes(id),
            id.toString().take(10),
            identityType,
        )
        if (developer) jdbcTemplate.update("INSERT INTO developers (user_id) VALUES (?)", uuidToBytes(id))
        return id
    }

    @Test
    fun `tryClaim 은 같은 임계값에 처음만 true 이고 이후 false 다`() {
        val threshold = 987_654_321L
        assertTrue(milestoneRepository.tryClaim(threshold))
        assertFalse(milestoneRepository.tryClaim(threshold))
    }

    @Test
    fun `announce 는 도달한 임계값에 1회 발송하고 재호출해도 다시 보내지 않는다`() {
        insertUser() // 사용자 최소 1명 이상 보장 (임계값 1 도달)
        stubSender.sent.clear()

        announcer(1).announce()
        assertEquals(1, stubSender.sent.size)
        val sent = stubSender.sent.first()
        assertEquals("test-channel", sent.channelId)
        assertEquals("달성 1", sent.embeds.first()["description"])

        announcer(1).announce()
        assertEquals(1, stubSender.sent.size) // 이미 발송한 임계값이라 재발송 없음
    }

    @Test
    fun `announce 는 마일스톤 채널이 비면 metrics 채널로 폴백한다`() {
        insertUser()
        stubSender.sent.clear()
        val fallback =
            UserMilestoneAnnouncer(
                metricsRepository,
                milestoneRepository,
                stubSender,
                AdminProperties(
                    userMilestoneThresholds = listOf(1),
                    userMilestoneChannelId = "", // 미지정 → metrics 채널로 폴백
                    userMilestoneMessage = "달성 {threshold}",
                    discordMetricsChannelId = "metrics-channel",
                ),
            )

        fallback.announce()

        assertEquals(1, stubSender.sent.size)
        assertEquals("metrics-channel", stubSender.sent.first().channelId)
    }

    @Test
    fun `announce 는 도달하지 않은 임계값은 발송하지 않는다`() {
        stubSender.sent.clear()

        announcer(Long.MAX_VALUE).announce()

        assertEquals(0, stubSender.sent.size)
        // 발송 안 됐으니 claim 도 안 됐다 — 내가 처음 claim 하면 true 여야 한다.
        assertTrue(milestoneRepository.tryClaim(Long.MAX_VALUE))
    }

    @Test
    fun `countAllUsers 는 개발진을 제외하고 탈퇴자·게스트를 포함해 센다`() {
        val beforeExcl = metricsRepository.countAllUsers(exclude = true)
        val beforeIncl = metricsRepository.countAllUsers(exclude = false)

        insertUser(identityType = "MEMBER")
        insertUser(identityType = "GUEST")
        insertUser(identityType = "MEMBER", withdrawn = true)
        insertUser(identityType = "MEMBER", developer = true)

        // 개발진 1명 제외, 탈퇴·게스트 포함 → +3
        assertEquals(beforeExcl + 3, metricsRepository.countAllUsers(exclude = true))
        // 전원 포함 → +4
        assertEquals(beforeIncl + 4, metricsRepository.countAllUsers(exclude = false))
    }
}
