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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets

// 백오피스 접근의 유일한 공개 표면 — Discord 슬래시커맨드(인터랙션) + grant 링크. 두 게이트 필터(EnvironmentAccessFilter·
// AdminAccessFilter)는 이 경로(/admin-access/**)를 항상 통과시킨다(여기서 IP 를 등록해야 게이트를 열 수 있으므로).
//
// Slack(HMAC)에서 Discord(Ed25519 인터랙션)로 이관(#654). `/piki-admin env:<dev|staging|prod>` 로 선택한 환경의 원타임
// 링크를 발급한다. 인터랙션은 한 엔드포인트로 오지만(Discord 앱당 URL 1개), 토큰을 HMAC 서명(GrantTokenCodec)해
// 대상 env 가 검증·소비하므로 cross-env 로 동작한다. 링크 클릭·IP 캡처·세션은 진입 표면과 무관해 그대로다.
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
) {
    // Discord 인터랙션 수신(application/json). Ed25519 서명 검증 후 분기:
    //   PING(type 1)  → PONG (Discord 가 엔드포인트 등록 시 이걸로 살아있음을 확인)
    //   커맨드(type 2) → 채널 게이트 → allowlist 게이트 → 선택한 env(옵션)의 원타임 grant 링크를 ephemeral embed 로
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
        if ((root.path("type").takeIf { it.canConvertToInt() }?.asInt() ?: 0) == TYPE_PING) return pong()

        // admin 전용 채널에서만 — 원타임 링크·대화형 대시보드 조회 등 admin 인터랙션을 한 채널로 국한한다(봇이 여러
        // 채널에 있어도). 채널 미설정(blank)이면 fail-closed 로 전부 거부(설정 실수로 아무 채널에서나 열리는 것 방지).
        val channelId = root.path("channel_id").takeIf { it.isString }?.asString() ?: ""
        if (adminProperties.discordAdminChannelId.isBlank() || channelId != adminProperties.discordAdminChannelId) {
            return embed(COLOR_RED, "❌ 사용 불가", "이 명령은 지정된 admin 채널에서만 사용할 수 있습니다.")
        }

        val userId = root.path("member").path("user").path("id").takeIf { it.isString }?.asString() ?: ""
        // 로그·감사 actor 이름 — 서버 별명(nick) 우선, 없으면 표시이름(global_name), 없으면 고유 핸들(username).
        val userName =
            root.path("member").path("nick").takeIf { it.isString }?.asString()
                ?: root.path("member").path("user").path("global_name").takeIf { it.isString }?.asString()
                ?: root.path("member").path("user").path("username").takeIf { it.isString }?.asString()
                ?: "unknown"

        // allowlist 게이트 — 허용된 Discord userId 만 링크를 받는다. 아니면 발급 없이 거부 UI(응답이 ephemeral 이라 본인만 봄).
        if (userId !in adminProperties.discordAdminUserIds) {
            auditService.record(userName, AdminAuditAction.ACCESS_DENIED, "미허용 Discord 계정의 admin 커맨드 시도", ClientIp.of(request))
            return embed(COLOR_RED, "❌ 접근 불가", "이 Discord 계정은 관리자 목록에 없습니다.")
        }

        return issueGrantLink(optionValue(root, "env"), userId, userName)
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

    // 선택한 env 의 원타임 링크를 만든다. host 는 grantHosts 맵에서(요청 host 가 아니라) — 인터랙션 엔드포인트와 대상 env 가
    // 다를 수 있기 때문. 토큰은 그 env 에 바인딩 서명돼, 대상 env 만 소비할 수 있다.
    private fun issueGrantLink(
        env: String,
        userId: String,
        name: String,
    ): Map<String, Any> {
        val host =
            adminProperties.grantHosts[env]
                ?: return embed(COLOR_RED, "❌ 알 수 없는 환경", "지원하지 않는 환경입니다: `$env`")
        val token = allowlistService.issueGrantToken(userId, name, env)
        val link = "$host/admin-access/grant?token=$token"
        return embed(
            COLOR_GREEN,
            "✅ 관리자 인증됨 — $name",
            "**$env** 접속: 이 기기에서 3분 내 아래 링크를 여세요 (그 기기 IP 가 등록됩니다).\n$link",
        )
    }

    // 최상위 커맨드 옵션 값(data.options[name==?].value). 서브커맨드 없이 env 옵션 하나라 인덱스 순회로 찾는다(Jackson 3).
    private fun optionValue(
        root: JsonNode,
        name: String,
    ): String {
        val opts = root.path("data").path("options")
        if (!opts.isArray) return ""
        for (i in 0 until opts.size()) {
            val o = opts.get(i)
            if (o.path("name").takeIf { it.isString }?.asString() == name) {
                return o.path("value").takeIf { it.isString }?.asString() ?: ""
            }
        }
        return ""
    }

    // Discord 인터랙션 응답: PONG(type 1).
    private fun pong(): Map<String, Any> = mapOf("type" to TYPE_PONG)

    // ephemeral(본인만 보임, flags 64) embed 응답(type 4). 링크가 채널에 새지 않게 항상 ephemeral.
    private fun embed(
        color: Int,
        title: String,
        description: String,
    ): Map<String, Any> =
        mapOf(
            "type" to TYPE_CHANNEL_MESSAGE,
            "data" to
                mapOf(
                    "embeds" to listOf(mapOf("title" to title, "description" to description, "color" to color)),
                    "flags" to FLAG_EPHEMERAL,
                ),
        )

    companion object {
        private const val TYPE_PING = 1
        private const val TYPE_PONG = 1
        private const val TYPE_CHANNEL_MESSAGE = 4
        private const val FLAG_EPHEMERAL = 64
        private const val COLOR_GREEN = 0x2ECC71
        private const val COLOR_RED = 0xE74C3C
    }
}
