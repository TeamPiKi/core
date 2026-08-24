package com.depromeet.piki.metrics.milestone

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import com.depromeet.piki.metrics.dashboard.MetricsRepository
import com.depromeet.piki.metrics.report.DiscordMessageSender
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// 가입자 수가 설정된 임계값에 도달하면 Discord 로 알림을 1회 보낸다.
// 임계값·채널·문구는 코드가 아니라 설정(AdminProperties, 환경변수)에 둔다 — 코드는 "설정된 임계값에 도달하면 설정된
// 문구를 보낸다" 는 일반 로직만 안다. 미설정(빈 임계값·빈 채널) 환경에선 아무 것도 하지 않는다.
// @ConditionalOnAdminEnabled: 운영 백오피스가 켜진 환경에서만 뜬다(Discord 발송 경계가 그 조건이라 결을 맞춘다).
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
        val thresholds = adminProperties.userMilestoneThresholds
        val channelId = adminProperties.userMilestoneChannelId
        // 미설정이면 조용히 skip — 이 기능이 켜지지 않은 환경(설정 없음)에서 매 가입마다 카운트 쿼리를 돌리지 않게 먼저 거른다.
        if (thresholds.isEmpty() || channelId.isBlank()) return

        // 가입자 기준(탈퇴 포함, 회원·게스트) 누적, 개발진 제외.
        val count = metricsRepository.countAllUsers(exclude = true)
        thresholds
            .filter { count >= it }
            .sorted()
            .forEach { threshold ->
                // 최초 claim 에 성공한 호출만 발송한다(중복 발송 방지). 발송은 트랜잭션 밖 외부 호출이다.
                if (milestoneRepository.tryClaim(threshold)) {
                    val sent = discordMessageSender.send(channelId, embedOf(threshold, count))
                    log.info("user milestone reached: threshold={} count={} sent={}", threshold, count, sent)
                }
            }
    }

    private fun embedOf(
        threshold: Long,
        count: Long,
    ): List<Map<String, Any>> {
        val description =
            adminProperties.userMilestoneMessage
                .replace(PLACEHOLDER_THRESHOLD, threshold.toString())
                .replace(PLACEHOLDER_COUNT, count.toString())
        return listOf(mapOf("description" to description, "color" to EMBED_COLOR))
    }

    companion object {
        // 문구 안에서 치환되는 자리표시자. 실제 문구·임계값은 설정에 있으므로 코드엔 자리표시자 키만 남는다.
        private const val PLACEHOLDER_THRESHOLD = "{threshold}"
        private const val PLACEHOLDER_COUNT = "{count}"
        private const val EMBED_COLOR = 0x5865F2 // Discord blurple
    }
}
