package com.depromeet.piki.admin.access

import com.depromeet.piki.admin.audit.AdminAuditAction
import com.depromeet.piki.admin.audit.AdminAuditService
import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.admin.config.ClientIp
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets

// 백오피스·운영 조회의 유일한 공개 표면 — Discord 슬래시커맨드(인터랙션) + grant 링크. 두 게이트 필터(EnvironmentAccessFilter·
// AdminAccessFilter)는 이 경로(/admin-access/**)를 항상 통과시킨다(여기서 IP 를 등록해야 게이트를 열 수 있으므로).
//
// Slack(HMAC)에서 Discord(Ed25519 인터랙션)로 이관(#654). Discord 앱당 인터랙션 URL 은 1개라, 이 컨트롤러가 공통
// 게이트(서명 검증 → PING → 채널 → allowlist)를 처리한 뒤 data.name 으로 DiscordCommandHandler 에 라우팅한다(#664):
//   piki-admin → AdminGrantCommandHandler (원타임 grant 링크)
//   stats      → StatsCommandHandler (대시보드 지표 조회)
@Hidden
@RestController
@ConditionalOnAdminEnabled
@RequestMapping("/admin-access")
class DiscordAccessController(
    private val verifier: DiscordInteractionVerifier,
    private val allowlistService: AdminAllowlistService,
    private val auditService: AdminAuditService,
    private val adminProperties: AdminProperties,
    private val objectMapper: ObjectMapper,
    handlers: List<DiscordCommandHandler>,
) {
    private val handlersByName: Map<String, DiscordCommandHandler> = handlers.associateBy { it.commandName }

    // Discord 인터랙션 수신(application/json). Ed25519 서명 검증 후 분기:
    //   PING(type 1)  → PONG (Discord 가 엔드포인트 등록 시 이걸로 살아있음을 확인)
    //   커맨드(type 2) → 채널 게이트 → allowlist 게이트 → data.name 라우팅 → 해당 핸들러 응답(ephemeral)
    @PostMapping("/discord", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun discord(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): Map<String, Any>? {
        val rawBody = request.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        val valid =
            verifier.verify(
                signatureHex = request.getHeader("X-Signature-Ed25519"),
                timestamp = request.getHeader("X-Signature-Timestamp"),
                rawBody = rawBody,
            )
        if (!valid) {
            // Discord 명세: 서명 무효는 401 이어야 한다. Discord 는 엔드포인트 URL 저장 시 일부러 틀린 서명을 보내
            // 401 이 오는지로 검증기 동작을 확인한다 — 200 을 주면 URL 등록이 거부된다.
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            return null
        }

        val root = objectMapper.readTree(rawBody)
        if ((root.path("type").takeIf { it.canConvertToInt() }?.asInt() ?: 0) == DiscordInteractions.TYPE_PING) {
            return DiscordInteractions.pong()
        }

        // admin 전용 채널에서만 — 원타임 링크·대화형 조회 등 운영 인터랙션을 한 채널로 국한한다(봇이 여러 채널에 있어도).
        // 채널 미설정(blank)이면 fail-closed 로 전부 거부(설정 실수로 아무 채널에서나 열리는 것 방지).
        val channelId = root.path("channel_id").takeIf { it.isString }?.asString() ?: ""
        if (adminProperties.discordAdminChannelId.isBlank() || channelId != adminProperties.discordAdminChannelId) {
            return DiscordInteractions.embed(DiscordInteractions.COLOR_RED, "❌ 사용 불가", "이 명령은 지정된 admin 채널에서만 사용할 수 있습니다.")
        }

        val userId = DiscordInteractions.userId(root)
        val userName = DiscordInteractions.userName(root)

        // allowlist 게이트 — 허용된 Discord userId 만 통과. 아니면 처리 없이 거부 UI(응답이 ephemeral 이라 본인만 봄).
        if (userId !in adminProperties.discordAdminUserIds) {
            auditService.record(userName, AdminAuditAction.ACCESS_DENIED, "미허용 Discord 계정의 admin 커맨드 시도", ClientIp.of(request))
            return DiscordInteractions.embed(DiscordInteractions.COLOR_RED, "❌ 접근 불가", "이 Discord 계정은 관리자 목록에 없습니다.")
        }

        // data.name 라우팅 — 등록되지 않은 커맨드는 거부(운영 실수·미배포 커맨드 방지).
        val handler =
            handlersByName[DiscordInteractions.commandName(root)]
                ?: return DiscordInteractions.embed(DiscordInteractions.COLOR_RED, "❌ 알 수 없는 명령", "지원하지 않는 명령입니다.")
        return handler.handle(DiscordInteraction(root, userId, userName, ClientIp.of(request)))
    }

    // grant 링크 클릭 — 토큰 검증(서명·만료·env·one-time) 후 접속자 IP 를 캡처해 등록 + 세션 발급(신원·IP 바인딩) → /admin.
    // 토큰은 다른 env 엔드포인트가 발급했을 수 있으나, 이 env 가 서명·env 일치를 확인해 소비한다.
    @GetMapping("/grant")
    fun grant(
        @RequestParam token: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val identity =
            allowlistService.consumeGrantToken(token, adminProperties.environment) ?: run {
                // setStatus 로 끝낸다(sendError 금지) — sendError 는 /error 로 ERROR 디스패치를 일으키고,
                // /error 는 admin 체인 밖이라 메인 JWT 체인이 401 로 가로채 링크가 늘 401 나던 버그가 있었다.
                response.status = HttpServletResponse.SC_NOT_FOUND
                response.contentType = "text/plain;charset=UTF-8"
                response.writer.write("링크가 만료됐거나 이미 사용됐거나 이 환경 링크가 아닙니다. Discord 에서 다시 발급하세요.")
                return
            }
        val ip = ClientIp.of(request)
        allowlistService.grant(ip, identity.name)
        AdminSession.establish(request.getSession(true), identity.userId, identity.name, ip)
        auditService.record(identity.name, AdminAuditAction.ACCESS_GRANTED, "원타임 링크로 접근 허용(IP 캡처)", ip)
        response.sendRedirect("/admin")
    }
}
