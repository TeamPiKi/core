package com.depromeet.piki.product.service.remote

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExtractionIdentityRuleTest {
    @ParameterizedTest(name = "[{0}] 은 통과한다")
    @ValueSource(strings = ["PiKi", "PiKi/2.0", "a.b_c-d/1.0", "1234567890123456789012345678901234567890"])
    fun `계약 형식의 이름은 통과한다`(identity: String) {
        assertNull(ExtractionIdentityRule.violationOf(identity))
    }

    @ParameterizedTest(name = "[{0}] 은 거부된다")
    @ValueSource(
        strings = [
            "PiKi (+https://example.com)",
            "https://example.com",
            "a/b/c",
            "/1.0",
            "PiKi/",
            "Pi Ki",
            "PiKi\n",
            "피키",
            "12345678901234567890123456789012345678901",
        ],
    )
    fun `형식을 어긴 이름은 거부된다`(identity: String) {
        assertNotNull(ExtractionIdentityRule.violationOf(identity))
    }

    @ParameterizedTest(name = "[{0}] 은 거부된다")
    @ValueSource(strings = ["pikibot", "PiKiBot/1.0", "Web-Crawler", "SPIDER", "PiKi/bot"])
    fun `크롤러 문자열이 든 이름은 대소문자와 위치에 상관없이 거부된다`(identity: String) {
        assertNotNull(ExtractionIdentityRule.violationOf(identity))
    }
}
