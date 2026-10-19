package com.depromeet.piki.notification.service

import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationTemplateRendererTest {
    private val renderer = NotificationTemplateRenderer()

    @Test
    fun `플레이스홀더를 변수 값으로 치환한다`() {
        val result = renderer.render("\${actorName}님이 토너먼트 후보를 담았어요", mapOf("actorName" to "철수"))
        assertEquals("철수님이 토너먼트 후보를 담았어요", result)
    }

    @Test
    fun `변수가 없는 템플릿은 원문 그대로 반환한다`() {
        val result = renderer.render("상품 정보가 저장됐어요", emptyMap())
        assertEquals("상품 정보가 저장됐어요", result)
    }

    @Test
    fun `맵에 없는 플레이스홀더는 치환하지 않고 원문으로 남긴다`() {
        val result = renderer.render("\${actorName}님 환영", emptyMap())
        assertEquals("\${actorName}님 환영", result)
    }

    @Test
    fun `여러 플레이스홀더를 모두 치환한다`() {
        val result = renderer.render("\${a} vs \${b}", mapOf("a" to "철수", "b" to "영희"))
        assertEquals("철수 vs 영희", result)
    }

    @Test
    fun `변수 값에 포함된 플레이스홀더는 재귀 치환하지 않는다`() {
        // actorName 등 사용자 입력이 변수 값으로 들어오므로, 값 안의 ${} 를 다시 치환하면 안 된다.
        // a 를 "${b}" 로 치환한 뒤, 그 결과 안의 ${b} 는 b 가 정의돼 있어도 재치환되지 않아야 한다.
        val result = renderer.render("\${a}", mapOf("a" to "\${b}", "b" to "치환됨"))
        assertEquals("\${b}", result)
    }
}
