package com.depromeet.piki.admin.access

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import org.springframework.stereotype.Component

// `/piki-admin env:<dev|prod> [dest:<home|reports>]` — 선택한 환경의 원타임 grant 링크를 발급한다(#654).
// host 는 grantHosts 맵에서(요청 host 가 아니라) — 인터랙션 엔드포인트와 대상 env 가 다를 수 있어서다.
// 토큰은 그 env 에 바인딩 서명돼(GrantTokenCodec), 대상 env 만 소비할 수 있다(cross-env).
//
// dest 는 링크를 열었을 때 도착하는 화면이다(#1291). 신고 알림을 받은 운영자가 홈을 거치지 않고 신고 목록으로
// 바로 가게 한다. 생략하면 홈이라 기존 사용법은 그대로다.
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
        val dest = destOf(DiscordInteractions.optionValue(interaction.root, "dest"))
        val token = allowlistService.issueGrantToken(interaction.userId, interaction.userName, env, dest)
        val link = "$host/admin-access/grant?token=$token"
        return DiscordInteractions.embed(
            DiscordInteractions.COLOR_GREEN,
            "✅ 관리자 인증됨 — ${interaction.userName}",
            "**$env** ${labelOf(dest)} 접속: 이 기기에서 3분 내 아래 링크를 여세요 (그 기기 IP 가 등록됩니다).\n$link",
        )
    }

    // 문서·스펙(DOCS·SPEC)은 /docs 커맨드가 따로 발급한다 — 여기서 고를 수 있게 두면 세션 없는 목적지가
    // admin 커맨드로 새어 "백오피스 링크인데 백오피스가 안 열리는" 혼선이 된다.
    private fun destOf(raw: String): GrantDest =
        when (raw) {
            "reports" -> GrantDest.REPORTS
            else -> GrantDest.ADMIN
        }

    private fun labelOf(dest: GrantDest): String =
        when (dest) {
            GrantDest.REPORTS -> "신고 목록"
            else -> "백오피스"
        }
}
