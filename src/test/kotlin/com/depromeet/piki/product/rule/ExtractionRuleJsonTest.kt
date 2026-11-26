package com.depromeet.piki.product.rule

import com.depromeet.piki.contracts.extraction.v1.ExtractionRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExtractionRuleJsonTest {
    @Test
    fun `정규형은 본문에 적힌 id 를 빼고 계약의 필드만 읽히는 모양의 한 줄로 남긴다`() {
        val canonical =
            ExtractionRuleJson.canonical(
                """
                {
                  "id": 99,
                  "host": "oliveyoung.co.kr",
                  "fetch": "RENDER",
                  "currentPrice": {"css": {"selector": "[class^='GoodsDetailInfo_price__'] span"}},
                  "absentOriginalPrice": "NO_DISCOUNT"
                }
                """.trimIndent(),
            )

        // 문자열을 통째로 고정한다 — 이 모양이 바뀌면 배포본 문자열이 바뀌어 두 인스턴스가 서로 덮어쓴다
        assertEquals(
            """{"host":"oliveyoung.co.kr","fetch":"RENDER",""" +
                """"currentPrice":{"css":{"selector":"[class^='GoodsDetailInfo_price__'] span"}},""" +
                """"absentOriginalPrice":"NO_DISCOUNT"}""",
            canonical,
        )
        val rule = ExtractionRuleJson.parse(canonical)
        assertEquals(0, rule.id)
        assertEquals(ExtractionRule.FetchMode.RENDER, rule.fetch)
    }

    @Test
    fun `정규형을 다시 정규화해도 같은 문자열이다`() {
        val once =
            ExtractionRuleJson.canonical(
                """{"marker": "meta[name=\"generator\"]", "name": {"json": {"script": "script#__NEXT_DATA__", "pointer": "/props/name"}}}""",
            )

        assertEquals(once, ExtractionRuleJson.canonical(once))
    }

    @Test
    fun `필드 이름의 오타는 무시하지 않고 거절한다`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                ExtractionRuleJson.canonical("""{"host": "oliveyoung.co.kr", "curentPrice": {"css": {"selector": ".price"}}}""")
            }

        assertEquals(true, failure.message?.contains("curentPrice"))
    }

    @Test
    fun `extractor 가 해석하지 못해 버릴 모양은 저장 전에 거절한다`() {
        listOf(
            """{"absentOriginalPrice": "NO_DISCOUNT"}""",
            """{"host": ""}""",
            """{"host": "https://www.oliveyoung.co.kr/store"}""",
            """{"host": "OliveYoung.co.kr"}""",
            """{"host": "localhost"}""",
            """{"marker": " "}""",
            """{"host": "a.com", "name": {}}""",
            """{"host": "a.com", "currentPrice": {"css": {}}}""",
            """{"host": "a.com", "name": {"json": {"script": "script", "pointer": "props/name"}}}""",
            """{"host": "a.com", "name": {"json": {"script": "", "pointer": "/props/name"}}}""",
            """{"host": "a.com", "name": {"css": {"selector": "${"div > ".repeat(200)}div"}}}""",
            """{"host": "a.com", "fetch": 5}""",
            """{"host": "a.com"}""",
            """{"marker": "meta[name='generator']", "fetch": "FETCH_MODE_UNSPECIFIED"}""",
            """{"host": "a.com", "fetch": "BROWSER"}""",
            """{"host": "oliveyoung.co.kr"""",
            "올리브영",
        ).forEach { input ->
            assertFailsWith<IllegalArgumentException>("거절해야 하는 입력: $input") { ExtractionRuleJson.canonical(input) }
        }
    }
}
