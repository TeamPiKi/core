package com.depromeet.piki.common.config

import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

// LocalDateTime 직렬화·역직렬화의 KST↔UTC 대칭 계약을 고정한다. 응답은 저장 UTC 를 KST(+09:00)로
// 표기하고, 그 값을 그대로 재전송해도 같은 instant 의 UTC LocalDateTime 으로 복원돼야 한다(요청/응답 대칭).
class JacksonConfigTest {
    private val mapper =
        JsonMapper.builder().addModule(JacksonConfig().localDateTimeModule()).build()

    @Test
    fun `저장 UTC LocalDateTime 을 KST(+09-00) 오프셋으로 직렬화한다`() {
        val utcStored = LocalDateTime.of(2026, 7, 7, 18, 30, 0)
        assertEquals("\"2026-07-08T03:30:00+09:00\"", mapper.writeValueAsString(utcStored))
    }

    @Test
    fun `응답의 KST(+09-00) 값을 재전송하면 같은 instant 의 UTC LocalDateTime 으로 복원한다`() {
        val parsed = mapper.readValue("\"2026-07-08T03:30:00+09:00\"", LocalDateTime::class.java)
        assertEquals(LocalDateTime.of(2026, 7, 7, 18, 30, 0), parsed)
    }

    @Test
    fun `offset 없는 LocalDateTime 문자열은 하위호환으로 그대로 파싱한다`() {
        val parsed = mapper.readValue("\"2026-07-07T18:30:00\"", LocalDateTime::class.java)
        assertEquals(LocalDateTime.of(2026, 7, 7, 18, 30, 0), parsed)
    }
}
