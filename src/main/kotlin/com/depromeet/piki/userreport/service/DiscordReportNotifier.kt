package com.depromeet.piki.userreport.service

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.userreport.service.dto.ReportNotification
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// 신고 알림을 디스코드 웹훅에 게시한다(#1291). 주간 리포트와 **다른 채널**이다 — 운영이 신고만 보는 자리다.
//
// 보내는 이름을 환경에서 만든다 — `dev-신고알림` · `prod-신고알림`. 웹훅을 쓰는 이유가 이것이다: 봇 토큰 경로는
// 이름을 지정할 수 없어 두 환경이 같은 이름으로 뜬다. 운영이 쓰는 Grafana 알림도 같은 방식으로 이름을 싣는다.
//
// 보내지 못하는 경우(웹훅 미설정·게시 실패)에 예외를 올리지 않는다. 알림은 부수 기능이고, 신고 자체는 이미
// 저장됐다. 운영이 알림을 못 받는 것과 사용자의 신고가 실패하는 것은 무게가 다르다.
@Component
class DiscordReportNotifier(
    private val webhookSender: DiscordWebhookSender,
    private val adminProperties: AdminProperties,
) : ReportNotifier {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun notify(notification: ReportNotification) {
        val webhookUrl = adminProperties.discordReportWebhookUrl
        if (webhookUrl.isBlank()) return logSkip("신고 알림 웹훅 미설정", notification)
        runCatching { webhookSender.send(webhookUrl, usernameOf(), embedOf(notification)) }
            .onFailure { log.warn("신고 알림 게시 실패 reportedId={}", notification.reportedId, it) }
    }

    // 환경명은 배포가 넣는다(ADMIN_ENVIRONMENT). 로컬은 비어 있어 local 로 둔다 — 이름이 비면 디스코드가 거부한다
    private fun usernameOf(): String = "${envLabel()}-신고알림"

    private fun envLabel(): String = adminProperties.environment.ifBlank { "local" }

    // 알림을 못 보낸 사실은 남긴다 — 운영이 "신고가 없었다" 와 "알림이 안 왔다" 를 구분할 수 있어야 한다
    private fun logSkip(
        why: String,
        notification: ReportNotification,
    ) {
        log.info("신고 알림 skip({}) reportedId={} 누적={}", why, notification.reportedId, notification.reportCountOfReported)
    }

    private fun embedOf(notification: ReportNotification): List<Map<String, Any>> =
        listOf(
            mapOf(
                "title" to "신고 접수 (누적 ${notification.reportCountOfReported}건)",
                "color" to EMBED_COLOR,
                "fields" to
                    listOf(
                        field("신고한 사용자", notification.reporterId.toString()),
                        field("신고된 사용자", notification.reportedId.toString()),
                        field("사유", notification.reason.name),
                        field("신고 시각 (KST)", kstOf(notification.reportedAt), inline = false),
                        field("상세", notification.detail?.takeIf { it.isNotBlank() } ?: "없음", inline = false),
                        // 진입 방법을 메시지에 싣는다 — 웹훅 메시지에는 버튼을 붙일 수 없고(디스코드가 앱 소유
                        // 웹훅에만 허용), 평문 링크는 채널 전원이 눌러 들어가므로 관리자 확인이 사라진다.
                        // 커맨드는 실행자가 허용 관리자인지 확인한 뒤 본인에게만 보이는 원타임 링크를 준다.
                        field("백오피스", "`/piki-admin env:${envLabel()} dest:reports`", inline = false),
                    ),
            ),
        )

    // 저장값은 UTC 다(JVM·DB 둘 다 UTC). 운영이 읽는 자리라 KST 로 바꿔 찍는다 — 디스코드 자동 타임스탬프에
    // 맡기지 않는 이유는 그 표시가 보는 사람의 로컬 타임존을 따라가 사람마다 달라지기 때문이다.
    private fun kstOf(utc: LocalDateTime): String =
        utc
            .atZone(ZoneOffset.UTC)
            .withZoneSameInstant(KST)
            .format(KST_FORMAT)

    private fun field(
        name: String,
        value: String,
        inline: Boolean = true,
    ): Map<String, Any> = mapOf("name" to name, "value" to value, "inline" to inline)

    companion object {
        // 주의를 끄는 붉은 계열 — 주간 리포트와 색으로도 구분된다
        private const val EMBED_COLOR = 0xE5484D
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private val KST_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
