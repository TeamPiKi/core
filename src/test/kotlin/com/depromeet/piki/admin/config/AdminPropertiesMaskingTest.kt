package com.depromeet.piki.admin.config

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

// 봇 토큰은 크리덴셜이라 toString 에 raw 로 새면 로그·에러에 노출된다. set 여부만 마스킹돼야 한다.
class AdminPropertiesMaskingTest {
    @Test
    fun `toString 은 봇 토큰 원문을 노출하지 않고 set 여부만 표시한다`() {
        val props = AdminProperties(discordBotToken = "super-secret-bot-token-value")

        val rendered = props.toString()

        assertFalse(rendered.contains("super-secret-bot-token-value"), "봇 토큰 원문이 toString 에 노출됨")
        assertContains(rendered, "discordBotToken=<set>")
    }

    @Test
    fun `봇 토큰이 비어 있으면 none 으로 표시한다`() {
        val props = AdminProperties(discordBotToken = "")

        assertContains(props.toString(), "discordBotToken=<none>")
    }
}
