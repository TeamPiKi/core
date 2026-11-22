package com.depromeet.piki.admin.access

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 신고 알림의 `백오피스에서 보기` 버튼(#1291). 누른 사람이 허용 관리자일 때만 원타임 grant 링크를 준다.
 *
 * 두 번 눌러야 들어가는 이유: 버튼 클릭은 **누가 눌렀는지**를 알려주지만 Discord 서버가 중계하므로 브라우저
 * IP 를 잡을 수 없고, 링크를 열면 IP 는 잡히지만 **누가 열었는지**를 모른다. 그래서 신원 확인(버튼)과
 * IP 캡처(Link 버튼)를 나눈다. 응답은 ephemeral 이라 링크가 채널에 새지 않는다.
 *
 * 알림을 보낸 환경과 이 인터랙션을 처리하는 환경이 다를 수 있다 — Discord 앱의 인터랙션 URL 은 하나다.
 * 그래서 custom_id 에 환경을 실어, 그 환경에 바인딩된 토큰을 발급한다(`/piki-admin env:` 와 같은 구조).
 */
@Component
@ConditionalOnAdminEnabled
class ReportGrantComponentHandler(
    private val allowlistService: AdminAllowlistService,
    private val adminProperties: AdminProperties,
) : DiscordComponentHandler {
    override val customIdPrefix = PREFIX

    override fun handle(
        interaction: DiscordInteraction,
        argument: String,
    ): Map<String, Any> {
        val parts = argument.split(ARG_SEPARATOR)
        if (parts.size != 2) return reject("버튼 정보가 올바르지 않습니다. 알림이 오래됐을 수 있어요.")
        val (env, rawTarget) = parts
        val host =
            adminProperties.grantHosts[env]
                ?: return reject("지원하지 않는 환경입니다: `$env`")
        // 경로를 만드는 값이라 UUID 로만 받는다 — 느슨하게 받으면 조립 결과를 예측할 수 없다
        val target =
            runCatching { UUID.fromString(rawTarget) }.getOrNull()
                ?: return reject("신고 대상 정보가 올바르지 않습니다.")

        val token =
            allowlistService.issueGrantToken(
                userId = interaction.userId,
                name = interaction.userName,
                env = env,
                dest = GrantDest.REPORTS,
                target = target,
            )
        return DiscordInteractions.embedWithLinkButtons(
            DiscordInteractions.COLOR_GREEN,
            "✅ 관리자 인증됨 — ${interaction.userName}",
            "**$env** 신고 상세로 바로 들어갑니다. 이 기기에서 3분 내 아래 버튼을 누르세요 (그 기기 IP 가 등록됩니다).",
            listOf("신고 상세 열기" to "$host/admin-access/grant?token=$token"),
        )
    }

    private fun reject(message: String): Map<String, Any> =
        DiscordInteractions.embed(DiscordInteractions.COLOR_RED, "❌ 처리 불가", message)

    companion object {
        const val PREFIX = "report-grant"
        private const val ARG_SEPARATOR = ":"

        /** 알림이 버튼에 심는 custom_id. 보낸 환경과 신고된 사람을 실어, 클릭을 그 둘로 되돌린다. */
        fun customIdOf(
            env: String,
            reportedId: UUID,
        ): String = "$PREFIX$ARG_SEPARATOR$env$ARG_SEPARATOR$reportedId"
    }
}
