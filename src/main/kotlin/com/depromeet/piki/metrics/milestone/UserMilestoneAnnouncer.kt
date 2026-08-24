package com.depromeet.piki.metrics.milestone

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import com.depromeet.piki.metrics.dashboard.MetricsRepository
import com.depromeet.piki.metrics.report.DiscordMessageSender
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// 누적 가입자 수가 interval 의 배수(기본 1000 → 1000·2000·3000…)를 넘을 때마다 Discord 로 알림을 1회 보낸다.
// 임계값은 코드(interval)로 두되, 게시 문구는 설정(AdminProperties, 환경변수)에서 읽는다 — 문구가 비면 기능 off.
// 채널은 주간 리포트와 같은 metrics 채널을 재사용한다(별도 채널 설정 없이 켤 수 있게).
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
        val message = adminProperties.userMilestoneMessage
        val channelId = adminProperties.discordMetricsChannelId
        val interval = adminProperties.userMilestoneInterval
        // 문구·채널 미설정, 또는 interval 이 비정상이면 조용히 skip — 켜지지 않은 환경에서 매 가입마다 카운트 쿼리를 돌리지 않게 먼저 거른다.
        if (message.isBlank() || channelId.isBlank() || interval <= 0) return

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

    // 도달한 배수를 문구의 {count} 자리에 넣는다. 문구 원문은 설정(코드 밖)에 있어 repo 엔 자리표시자 키만 남는다.
    private fun embedOf(milestone: Long): List<Map<String, Any>> {
        val description = adminProperties.userMilestoneMessage.replace(PLACEHOLDER_COUNT, milestone.toString())
        return listOf(mapOf("description" to description, "color" to EMBED_COLOR))
    }

    companion object {
        private const val PLACEHOLDER_COUNT = "{count}"
        private const val EMBED_COLOR = 0x5865F2 // Discord blurple
    }
}
