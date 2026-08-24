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

// 사용자 수 마일스톤 알림. announcer 는 interval·문구를 설정에서 읽으므로, 테스트는 명시 설정으로 announcer 를 직접
// 구성해(실제 repo + stub Discord) 오케스트레이션을 검증한다. 실제 interval(1000)은 테스트에서 채우기 어려우므로
// interval 을 작게 준다. (Spring 빈 announcer 는 test 설정 문구가 비어 inert)
@Transactional
class UserMilestoneAnnouncerIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var metricsRepository: MetricsRepository

    @Autowired private lateinit var milestoneRepository: UserMilestoneRepository

    @Autowired private lateinit var stubSender: StubDiscordMessageSender

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    private fun announcer(
        interval: Long,
        environment: String = "prod",
    ) = UserMilestoneAnnouncer(
        metricsRepository,
        milestoneRepository,
        stubSender,
        AdminProperties(
            environment = environment,
            discordMetricsChannelId = "metrics-channel",
            userMilestoneInterval = interval,
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
    fun `tryClaim 은 같은 배수에 처음만 true 이고 이후 false 다`() {
        val milestone = 987_654_000L
        assertTrue(milestoneRepository.tryClaim(milestone))
        assertFalse(milestoneRepository.tryClaim(milestone))
    }

    @Test
    fun `announce 는 interval 배수 도달 시 metrics 채널로 1회 발송하고 재호출해도 다시 보내지 않는다`() {
        insertUser() // 사용자 최소 1명 이상 보장 (interval 1 배수 도달)
        stubSender.sent.clear()
        stubSender.result = true
        val expectedMilestone = metricsRepository.countAllUsers(exclude = true) // interval=1 이라 배수 = 현재 카운트

        announcer(1).announce()
        assertEquals(1, stubSender.sent.size)
        val sent = stubSender.sent.first()
        assertEquals("metrics-channel", sent.channelId)
        // 문구는 하드코딩 상수({count} 치환) — 도달 배수와 PiKi 가 담겼는지 확인한다.
        val description = sent.embeds.first()["description"] as String
        assertEquals(UserMilestoneAnnouncer.MESSAGE.replace("{count}", expectedMilestone.toString()), description)

        announcer(1).announce()
        assertEquals(1, stubSender.sent.size) // 이미 발송한 배수라 재발송 없음
    }

    @Test
    fun `announce 는 Discord 발송 실패 시 claim 을 해제해 다음 가입에서 재시도한다`() {
        insertUser()
        stubSender.sent.clear()
        stubSender.result = false // 발송 실패 시나리오
        val milestone = metricsRepository.countAllUsers(exclude = true)

        announcer(1).announce()

        assertEquals(1, stubSender.sent.size) // 발송은 시도됨
        // 실패했으니 claim 이 해제돼야 한다 — 지금 claim 하면 true(비어 있어 최초 claim).
        assertTrue(milestoneRepository.tryClaim(milestone))
    }

    @Test
    fun `announce 는 첫 배수(interval)에 못 미치면 발송하지 않는다`() {
        stubSender.sent.clear()
        stubSender.result = true

        announcer(Long.MAX_VALUE).announce() // 현재 카운트로는 절대 못 넘는 interval

        assertEquals(0, stubSender.sent.size)
    }

    @Test
    fun `announce 는 prod 가 아니면(dev 등) 발송하지 않는다`() {
        insertUser()
        stubSender.sent.clear()
        stubSender.result = true

        announcer(interval = 1, environment = "dev").announce() // 배수 도달해도 dev 라 skip

        assertEquals(0, stubSender.sent.size)
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
