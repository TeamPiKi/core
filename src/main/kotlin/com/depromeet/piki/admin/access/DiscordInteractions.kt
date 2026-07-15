package com.depromeet.piki.admin.access

import tools.jackson.databind.JsonNode

// Discord 인터랙션 페이로드 파싱·응답 조립 공통 헬퍼(순수). 여러 커맨드 핸들러가 공유한다.
object DiscordInteractions {
    const val TYPE_PING = 1
    const val TYPE_PONG = 1
    const val TYPE_CHANNEL_MESSAGE = 4
    const val FLAG_EPHEMERAL = 64
    const val COLOR_GREEN = 0x2ECC71
    const val COLOR_RED = 0xE74C3C

    // data.name — 어느 슬래시커맨드인가(라우팅 키).
    fun commandName(root: JsonNode): String = root.path("data").path("name").takeIf { it.isString }?.asString() ?: ""

    // 최상위 커맨드 옵션 값(data.options[name==?].value). 서브커맨드 없이 인덱스 순회로 찾는다(Jackson 3).
    fun optionValue(
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

    fun userId(root: JsonNode): String = root.path("member").path("user").path("id").takeIf { it.isString }?.asString() ?: ""

    // 로그·감사 actor 이름 — 서버 별명(nick) 우선, 없으면 표시이름(global_name), 없으면 고유 핸들(username).
    fun userName(root: JsonNode): String =
        root.path("member").path("nick").takeIf { it.isString }?.asString()
            ?: root.path("member").path("user").path("global_name").takeIf { it.isString }?.asString()
            ?: root.path("member").path("user").path("username").takeIf { it.isString }?.asString()
            ?: "unknown"

    fun pong(): Map<String, Any> = mapOf("type" to TYPE_PONG)

    // ephemeral(본인만 보임, flags 64) 단일 embed(제목+설명). 링크·거부 UI 가 채널에 새지 않게 항상 ephemeral.
    fun embed(
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

    // embed + Link 버튼(style 5, URL). 클릭 시 사용자 브라우저에서 URL 이 열려 그 기기 IP 를 캡처해야 하는 경우에 쓴다(#733 문서 grant).
    // 인터랙션 버튼(type 2 + custom_id)은 Discord 서버가 처리해 캡처 IP 가 Discord 것이 되므로 grant 에 못 쓴다 — Link 버튼만 기기 IP 를 잡는다.
    fun embedWithLinkButtons(
        color: Int,
        title: String,
        description: String,
        buttons: List<Pair<String, String>>,
    ): Map<String, Any> =
        mapOf(
            "type" to TYPE_CHANNEL_MESSAGE,
            "data" to
                mapOf(
                    "embeds" to listOf(mapOf("title" to title, "description" to description, "color" to color)),
                    "flags" to FLAG_EPHEMERAL,
                    "components" to
                        listOf(
                            mapOf(
                                "type" to 1, // action row
                                "components" to
                                    buttons.map { (label, url) ->
                                        mapOf("type" to 2, "style" to 5, "label" to label, "url" to url) // link button
                                    },
                            ),
                        ),
                ),
        )
}

// 게이트(서명·채널·allowlist)를 통과한 인터랙션 컨텍스트. 핸들러는 이 값만 받아 커맨드를 처리한다.
data class DiscordInteraction(
    val root: JsonNode,
    val userId: String,
    val userName: String,
    val clientIp: String,
)

// data.name 별 커맨드 처리기. 게이트는 컨트롤러가 공통으로 하고, 핸들러는 자기 커맨드 로직만 담는다.
interface DiscordCommandHandler {
    val commandName: String

    fun handle(interaction: DiscordInteraction): Map<String, Any>
}
