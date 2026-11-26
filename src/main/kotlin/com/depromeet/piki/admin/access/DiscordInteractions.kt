package com.depromeet.piki.admin.access

import tools.jackson.databind.JsonNode

// Discord 인터랙션 페이로드 파싱·응답 조립 공통 헬퍼(순수). 여러 커맨드 핸들러가 공유한다.
object DiscordInteractions {
    const val TYPE_PING = 1
    const val TYPE_PONG = 1
    const val TYPE_APPLICATION_COMMAND = 2
    const val TYPE_MESSAGE_COMPONENT = 3
    const val TYPE_CHANNEL_MESSAGE = 4
    const val FLAG_EPHEMERAL = 64
    const val COLOR_GREEN = 0x2ECC71
    const val COLOR_RED = 0xE74C3C

    // data.name — 어느 슬래시커맨드인가(라우팅 키).
    fun commandName(root: JsonNode): String = root.path("data").path("name").takeIf { it.isString }?.asString() ?: ""

    // data.custom_id — 어느 버튼을 눌렀나(컴포넌트 인터랙션의 라우팅 키).
    fun customId(root: JsonNode): String = root.path("data").path("custom_id").takeIf { it.isString }?.asString() ?: ""

    fun interactionType(root: JsonNode): Int = root.path("type").takeIf { it.canConvertToInt() }?.asInt() ?: 0

    fun channelId(root: JsonNode): String = root.path("channel_id").takeIf { it.isString }?.asString() ?: ""

    /**
     * 이 인터랙션을 이 채널에서 받아도 되는가. 슬래시커맨드는 admin 채널, 버튼은 신고 알림 채널에서만 받는다(#1291).
     *
     * 버튼을 admin 채널 기준으로 재면 늘 거부된다 — 알림이 게시되는 곳은 신고 채널이기 때문이다. 반대로 두 채널을
     * 합쳐 허용하면 신고 채널에서 슬래시커맨드가 돌아 운영 인터랙션이 그 채널로 새어 나간다.
     *
     * 설정이 비어 있으면 거부한다(fail-closed). 빈 값을 통과시키면 설정 실수가 "아무 채널에서나 열림" 이 된다.
     */
    fun isAllowedChannel(
        channelId: String,
        interactionType: Int,
        adminChannelId: String,
        reportChannelId: String,
    ): Boolean {
        val allowed =
            when (interactionType) {
                TYPE_MESSAGE_COMPONENT -> reportChannelId
                else -> adminChannelId
            }
        return allowed.isNotBlank() && channelId == allowed
    }

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

/**
 * 버튼 클릭(컴포넌트 인터랙션) 처리기(#1291). 커맨드와 같은 게이트(서명·채널·allowlist)를 통과한 뒤 불린다.
 *
 * custom_id 는 `<prefix>:<인자>` 모양이고, prefix 로 라우팅한다. 인자 파싱은 각 핸들러가 한다 —
 * 버튼마다 인자 모양이 다르고, 잘못된 인자를 거부하는 방식도 그 버튼의 책임이다.
 */
interface DiscordComponentHandler {
    val customIdPrefix: String

    fun handle(
        interaction: DiscordInteraction,
        argument: String,
    ): Map<String, Any>
}
