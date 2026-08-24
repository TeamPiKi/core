package com.depromeet.piki.metrics.milestone

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import com.depromeet.piki.metrics.dashboard.MetricsRepository
import com.depromeet.piki.metrics.report.DiscordMessageSender
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// 누적 가입자 수가 interval 의 배수(기본 1000 → 1000·2000·3000…)를 넘을 때마다 Discord 로 축하 알림을 1회 보낸다.
// 채널은 주간 리포트와 같은 metrics 채널을 재사용한다(채널 미설정이면 off).
// @ConditionalOnAdminEnabled: Discord 발송 경계가 뜨는 운영 백오피스 환경에서만 로드된다.
@Component
@ConditionalOnAdminEnabled
class UserMilestoneAnnouncer(
    private val metricsRepository: MetricsRepository,
    private val milestoneRepository: UserMilestoneRepository,
    private val discordMessageSender: DiscordMessageSender,
    private val adminProperties: AdminProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun announce() {
        // 축하 알림은 prod 만 — metrics 채널은 팀 공용이라 dev 가 테스트 데이터로 발송하면 안 된다(주간 리포트 스케줄러와 같은 게이트).
        if (adminProperties.environment != "prod") return
        val channelId = adminProperties.discordMetricsChannelId
        val interval = adminProperties.userMilestoneInterval
        // 채널 미설정 또는 interval 이 비정상이면 조용히 skip.
        if (channelId.isBlank() || interval <= 0) return

        // 가입자 기준(탈퇴 포함, 회원·게스트) 누적, 개발진 제외.
        val count = metricsRepository.countAllUsers(exclude = true)
        // 지금까지 넘긴 가장 최근 interval 배수. 아직 첫 배수(interval)에 못 미치면 발송할 게 없다.
        val milestone = count / interval * interval
        if (milestone < interval) return

        // 최초 claim 에 성공한 호출만 발송한다(중복 발송 방지, INSERT IGNORE 원자성). 이미 발송한 배수면 tryClaim 이 false.
        if (milestoneRepository.tryClaim(milestone)) {
            val sent =
                runCatching { discordMessageSender.send(channelId, embedOf(milestone)) }
                    .onFailure { log.warn("user milestone {} Discord 발송 예외", milestone, it) }
                    .getOrDefault(false)
            if (sent) {
                log.info("user milestone reached: milestone={} count={}", milestone, count)
            } else {
                // 발송 실패 시 claim 을 해제해 다음 가입에서 재시도되게 한다 — 발송 안 된 마일스톤이 영구 유실되지 않도록.
                log.warn("user milestone {} 발송 실패 — claim 해제(다음 가입에서 재시도)", milestone)
                milestoneRepository.release(milestone)
            }
        }
    }

    // 도달한 배수를 축하 문구의 {count} 자리에 넣는다.
    private fun embedOf(milestone: Long): List<Map<String, Any>> {
        val description = MESSAGE.replace(PLACEHOLDER_COUNT, milestone.toString())
        return listOf(mapOf("description" to description, "color" to EMBED_COLOR))
    }

    companion object {
        private const val PLACEHOLDER_COUNT = "{count}"
        private const val EMBED_COLOR = 0x5865F2 // Discord blurple

        // 마일스톤 축하 문구. {count} 에 도달한 배수가 들어간다.
        internal const val MESSAGE =
            "🎉 PiKi 누적 가입자 {count}명 달성! 🎉\n" +
                "의연 · 예빈 · 선아 · 하은 · 소영 · 영찬 · 재중 · 세빈 — 다들 고생했어요 👏🥳🚀"
    }
}
