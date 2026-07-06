package com.depromeet.piki.admin.access

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import org.springframework.stereotype.Component

// `/piki-admin env:<dev|staging|prod>` — 선택한 환경의 원타임 grant 링크를 발급한다(#654).
// host 는 grantHosts 맵에서(요청 host 가 아니라) — 인터랙션 엔드포인트와 대상 env 가 다를 수 있어서다.
// 토큰은 그 env 에 바인딩 서명돼(GrantTokenCodec), 대상 env 만 소비할 수 있다(cross-env).
@Component
@ConditionalOnAdminEnabled
class AdminGrantCommandHandler(
    private val allowlistService: AdminAllowlistService,
    private val adminProperties: AdminProperties,
) : DiscordCommandHandler {
    override val commandName = "piki-admin"

    override fun handle(interaction: DiscordInteraction): Map<String, Any> {
        val env = DiscordInteractions.optionValue(interaction.root, "env")
        val host =
            adminProperties.grantHosts[env]
                ?: return DiscordInteractions.embed(DiscordInteractions.COLOR_RED, "❌ 알 수 없는 환경", "지원하지 않는 환경입니다: `$env`")
        val token = allowlistService.issueGrantToken(interaction.userId, interaction.userName, env)
        val link = "$host/admin-access/grant?token=$token"
        return DiscordInteractions.embed(
            DiscordInteractions.COLOR_GREEN,
            "✅ 관리자 인증됨 — ${interaction.userName}",
            "**$env** 접속: 이 기기에서 3분 내 아래 링크를 여세요 (그 기기 IP 가 등록됩니다).\n$link",
        )
    }
}
