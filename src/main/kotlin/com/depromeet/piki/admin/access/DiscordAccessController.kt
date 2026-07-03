package com.depromeet.piki.admin.access

import com.depromeet.piki.admin.audit.AdminAuditAction
import com.depromeet.piki.admin.audit.AdminAuditService
import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.admin.config.ClientIp
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
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
// Slack(HMAC)에서 Discord(Ed25519 인터랙션)로 이관(#654). 링크 클릭(grant)·IP 캡처·세션·게이트는 그대로다 —
// 바뀐 건 "누가 링크를 받을 수 있나"의 진입 검증(서명 + allowlist)뿐.
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
    private val log = LoggerFactory.getLogger(javaClass)

    // Discord 인터랙션 수신(application/json). Ed25519 서명 검증 후 분기:
    //   PING(type 1)      → PONG (Discord 가 엔드포인트 등록 시 이걸로 살아있음을 확인)
    //   커맨드(type 2)     → allowlist 확인 후 서브커맨드 처리
    //     (없음)|grant     → 원타임 grant 링크 발급(그 기기에서 링크 열면 IP 자동 캡처)
    //     list            → 현재 허용 IP 목록
    //     revoke ip:<ip>  → 해제
    //     allow ip:<ip>   → 직접 등록
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
        val userName = root.path("member").path("user").path("username").takeIf { it.isString }?.asString() ?: "unknown"

        // allowlist 게이트 — 허용된 Discord userId 만 링크·관리 명령을 쓸 수 있다. 아니면 발급 없이 거부 UI.
        if (userId !in adminProperties.discordAdminUserIds) {
            auditService.record(userName, AdminAuditAction.ACCESS_DENIED, "미허용 Discord 계정의 admin 커맨드 시도", ClientIp.of(request))
            return deniedEmbed()
        }

        val sub = firstOption(root)
        return when (sub?.path("name")?.takeIf { it.isString }?.asString()) {
            "list" -> listAllowed()
            "revoke" -> revoke(optionValue(sub, "ip"), userName)
            "allow" -> grantDirect(optionValue(sub, "ip"), userName)
            else -> issueGrantLink(request, userId, userName)
        }
    }

    // grant 링크 클릭 — 토큰 검증 후 접속자 IP 를 자동 캡처해 등록 + 세션 발급(신원·IP 바인딩) → /admin 이동.
    // Slack/Discord 무관한 공용 흐름이라 진입 표면이 바뀌어도 그대로다.
    @GetMapping("/grant")
    fun grant(
        @RequestParam token: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val identity =
            allowlistService.consumeGrantToken(token) ?: run {
                // setStatus 로 끝낸다(sendError 금지) — sendError 는 /error 로 ERROR 디스패치를 일으키고,
                // /error 는 admin 체인 밖이라 메인 JWT 체인이 401 로 가로채 링크가 늘 401 나던 버그가 있었다.
                response.status = HttpServletResponse.SC_NOT_FOUND
                response.contentType = "text/plain;charset=UTF-8"
                response.writer.write("링크가 만료됐거나 유효하지 않습니다. Discord 에서 다시 발급하세요.")
                return
            }
        val ip = ClientIp.of(request)
        allowlistService.grant(ip, identity.name)
        AdminSession.establish(request.getSession(true), identity.userId, identity.name, ip)
        auditService.record(identity.name, AdminAuditAction.ACCESS_GRANTED, "원타임 링크로 접근 허용(IP 캡처)", ip)
        response.sendRedirect("/admin")
    }

    private fun issueGrantLink(
        request: HttpServletRequest,
        discordUserId: String,
        discordName: String,
    ): Map<String, Any> {
        val token = allowlistService.issueGrantToken(discordUserId, discordName)
        val link = "${baseUrl(request)}/admin-access/grant?token=$token"
        return embed(
            COLOR_GREEN,
            "✅ 관리자 인증됨 — $discordName",
            "접속할 기기에서 아래 링크를 3분 내 여세요 (그 기기 IP 가 등록됩니다):\n$link",
        )
    }

    private fun grantDirect(
        ip: String,
        discordName: String,
    ): Map<String, Any> {
        // 오타로 엉뚱한 값이 allowlist 키로 박히는 걸 막는다 — 형식 안 맞으면 등록하지 않고 안내만.
        if (!isValidIp(ip)) return embed(COLOR_RED, "❌ 등록 실패", "유효한 IP 형식이 아닙니다: `$ip` (예: 121.130.45.67)")
        allowlistService.grant(ip, discordName)
        auditService.record(discordName, AdminAuditAction.ACCESS_GRANTED, "직접 입력으로 IP $ip 허용", ip)
        return embed(COLOR_GREEN, "✅ 허용됨", "IP $ip 를 허용했습니다 (등록자: $discordName).")
    }

    private fun listAllowed(): Map<String, Any> {
        val lines = allowlistService.list().joinToString("\n") { "• ${it.ip} — ${it.name}" }.ifBlank { "(허용된 IP 없음)" }
        return embed(COLOR_GREEN, "현재 허용된 IP", lines)
    }

    private fun revoke(
        ip: String,
        discordName: String,
    ): Map<String, Any> {
        allowlistService.revoke(ip)
        auditService.record(discordName, AdminAuditAction.ACCESS_REVOKED, "IP $ip 허용 해제", ip)
        return embed(COLOR_GREEN, "해제됨", "IP $ip 허용을 해제했습니다.")
    }

    // 서브커맨드 노드(data.options[0]). 없으면 null(= 기본 grant).
    private fun firstOption(root: JsonNode): JsonNode? {
        val options = root.path("data").path("options")
        return if (options.isArray && options.size() > 0) options.get(0) else null
    }

    // 서브커맨드의 옵션 값(data.options[0].options[name=?].value). JsonNode 인덱스 순회(Jackson 3).
    private fun optionValue(
        sub: JsonNode,
        name: String,
    ): String {
        val opts = sub.path("options")
        if (!opts.isArray) return ""
        for (i in 0 until opts.size()) {
            val o = opts.get(i)
            if (o.path("name").takeIf { it.isString }?.asString() == name) {
                return o.path("value").takeIf { it.isString }?.asString() ?: ""
            }
        }
        return ""
    }

    // IPv4 는 옥텟 범위까지 엄격히, IPv6 는 hex·콜론 구성만 느슨히 본다(정확한 파싱보다 오타 차단이 목적).
    private fun isValidIp(ip: String): Boolean {
        IPV4.matchEntire(ip)?.let { m -> return m.groupValues.drop(1).all { it.toInt() in 0..255 } }
        return ip.contains(":") && ip.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
    }

    // nginx 가 넘기는 X-Forwarded-* 로 외부에서 본 base URL 을 만든다(컨테이너 내부 localhost:8080 가 아니라).
    private fun baseUrl(request: HttpServletRequest): String {
        val proto = request.getHeader("X-Forwarded-Proto")?.ifBlank { null } ?: request.scheme
        val host = request.getHeader("X-Forwarded-Host")?.ifBlank { null } ?: request.getHeader("Host") ?: "localhost"
        return "$proto://$host"
    }

    // Discord 인터랙션 응답: PONG(type 1).
    private fun pong(): Map<String, Any> = mapOf("type" to TYPE_PONG)

    private fun deniedEmbed(): Map<String, Any> =
        embed(COLOR_RED, "❌ 접근 불가", "이 Discord 계정은 관리자 목록에 없습니다.")

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
        private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    }
}
