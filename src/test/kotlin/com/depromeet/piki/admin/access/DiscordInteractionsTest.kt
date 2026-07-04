package com.depromeet.piki.admin.access

import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals

// 인터랙션 페이로드 파싱 헬퍼(순수). 옵션·커맨드명·유저 추출을 단위로 고정한다.
class DiscordInteractionsTest {
    private val mapper = ObjectMapper()

    @Test
    fun `data options 에서 이름으로 옵션 값을 뽑고 없으면 빈 문자열이다`() {
        val root = mapper.readTree("""{"data":{"name":"stats","options":[{"name":"period","value":"7d"},{"name":"metric","value":"wish"}]}}""")

        assertEquals("7d", DiscordInteractions.optionValue(root, "period"))
        assertEquals("wish", DiscordInteractions.optionValue(root, "metric"))
        assertEquals("", DiscordInteractions.optionValue(root, "absent"))
    }

    @Test
    fun `커맨드 이름을 뽑는다`() {
        val root = mapper.readTree("""{"data":{"name":"piki-admin"}}""")
        assertEquals("piki-admin", DiscordInteractions.commandName(root))
    }

    @Test
    fun `유저 표시이름은 nick 우선, 없으면 global_name, 없으면 username 이다`() {
        val nick = mapper.readTree("""{"member":{"nick":"별명","user":{"id":"1","global_name":"글로벌","username":"핸들"}}}""")
        val global = mapper.readTree("""{"member":{"user":{"id":"1","global_name":"글로벌","username":"핸들"}}}""")
        val handle = mapper.readTree("""{"member":{"user":{"id":"1","username":"핸들"}}}""")

        assertEquals("별명", DiscordInteractions.userName(nick))
        assertEquals("글로벌", DiscordInteractions.userName(global))
        assertEquals("핸들", DiscordInteractions.userName(handle))
        assertEquals("1", DiscordInteractions.userId(nick))
    }
}
