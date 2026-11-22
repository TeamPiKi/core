package com.depromeet.piki.userreport.service

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.metrics.report.DiscordMessageSender
import com.depromeet.piki.userreport.service.dto.ReportNotification
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

// 신고 알림을 디스코드 채널에 게시한다(#1291). 주간 리포트와 **다른 채널**이다 — 운영이 신고만 보는 자리다.
//
// DiscordMessageSender 를 ObjectProvider 로 받는 이유: 그 빈이 admin 설정에 묶여 있어(ConditionalOnAdminEnabled)
// admin 이 꺼진 환경에선 아예 없다. 생성자로 직접 받으면 그 환경에서 컨텍스트가 뜨지 않는다.
//
// 보내지 못하는 경우(전송기 없음·채널 미설정·게시 실패)에 예외를 올리지 않는다. 알림은 부수 기능이고,
// 신고 자체는 이미 저장됐다. 운영이 알림을 못 받는 것과 사용자의 신고가 실패하는 것은 무게가 다르다.
@Component
class DiscordReportNotifier(
    private val senderProvider: ObjectProvider<DiscordMessageSender>,
    private val adminProperties: AdminProperties,
) : ReportNotifier {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun notify(notification: ReportNotification) {
        val sender = senderProvider.getIfAvailable() ?: return logSkip("디스코드 전송기 없음(admin 꺼짐)", notification)
        val channelId = adminProperties.discordReportChannelId
        if (channelId.isBlank()) return logSkip("신고 알림 채널 미설정", notification)
        runCatching { sender.send(channelId, embedOf(notification)) }
            .onFailure { log.warn("신고 알림 게시 실패 reportedId={}", notification.reportedId, it) }
    }

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
                        field("신고된 사용자", notification.reportedId.toString()),
                        field("가입일", notification.reportedJoinedAt.toLocalDate().toString()),
                        field("회원 여부", if (notification.reportedIsMember) "회원" else "게스트"),
                        field("폴더 · 위시", "${notification.reportedFolderCount} · ${notification.reportedWishCount}"),
                        field("신고한 사용자", notification.reporterId.toString()),
                        field("사유", notification.reason.name),
                        field("상세", notification.detail?.takeIf { it.isNotBlank() } ?: "없음", inline = false),
                    ),
            ),
        )

    private fun field(
        name: String,
        value: String,
        inline: Boolean = true,
    ): Map<String, Any> = mapOf("name" to name, "value" to value, "inline" to inline)

    companion object {
        // 주의를 끄는 붉은 계열 — 주간 리포트와 색으로도 구분된다
        private const val EMBED_COLOR = 0xE5484D
    }
}
