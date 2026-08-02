package com.depromeet.piki.admin.access

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import org.springframework.stereotype.Component

// `/docs` — dev API 레퍼런스 문서 접근 grant(#733). Link 버튼 2개([API 문서]·[api.json])를 발급한다.
// 문서는 dev 만 노출하므로 env 는 dev 고정(prod 는 springdoc off 로 문서 자체가 404). 버튼을 사용자 브라우저에서
// 열면 그 기기 IP 가 allowlist 에 등록되고(1h sliding), 이후 그 IP 에서 /docs·/v3/api-docs 둘 다 열린다(세션 없이 IP 만).
//
// 두 버튼은 각각 grant 토큰(DOCS·SPEC)이라 목적지 페이지로 바로 리다이렉트한다. 하나만 눌러 IP 가 등록되면 나머지
// 페이지는 3분(토큰 수명)과 무관하게 1시간 동안 주소창으로 접근 가능하다 — 버튼은 목적지 편의일 뿐이다.
@Component
@ConditionalOnAdminEnabled
class DocsCommandHandler(
    private val allowlistService: AdminAllowlistService,
    private val adminProperties: AdminProperties,
) : DiscordCommandHandler {
    override val commandName = "docs"

    override fun handle(interaction: DiscordInteraction): Map<String, Any> {
        val host =
            adminProperties.grantHosts[DEV_ENV]
                ?: return DiscordInteractions.embed(
                    DiscordInteractions.COLOR_RED,
                    "❌ 설정 오류",
                    "dev 문서 host 가 설정되지 않았습니다.",
                )
        val docsToken = allowlistService.issueGrantToken(interaction.userId, interaction.userName, DEV_ENV, GrantDest.DOCS)
        val specToken = allowlistService.issueGrantToken(interaction.userId, interaction.userName, DEV_ENV, GrantDest.SPEC)
        return DiscordInteractions.embedWithLinkButtons(
            DiscordInteractions.COLOR_GREEN,
            "📄 dev API 문서 접근 — ${interaction.userName}",
            "아래 버튼을 이 기기 브라우저에서 여세요 (3분 내). 한 번 열면 그 IP 가 1시간 등록돼 두 페이지 다 접근됩니다.",
            listOf(
                "API 문서" to "$host/admin-access/grant?token=$docsToken",
                "api.json" to "$host/admin-access/grant?token=$specToken",
            ),
        )
    }

    private companion object {
        const val DEV_ENV = "dev"
    }
}
