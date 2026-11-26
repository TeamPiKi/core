package com.depromeet.piki.product.service.remote

import com.depromeet.piki.contracts.extraction.v1.ExtractionRule
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.routing.DomainAccess
import com.depromeet.piki.product.routing.DomainAccessPolicy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 시험 추출 호출이 계약(rpc RuleProbe)의 요청·응답 모양과 맞물리는지 고정한다. 여기서 나온 결과와 사유가 그대로
// 백오피스의 저장 게이트가 되므로, 응답의 각 갈래가 무엇으로 읽히는지까지 단언한다.
class HttpExtractionRuleProbeTest {
    private val probeUrl = "http://extractor.test/internal/rules/probe"
    private val link = ProductLink.parse("https://www.oliveyoung.co.kr/store/goods/getGoodsDetail.do?goodsNo=A000000171788")
    private val rule: ExtractionRule =
        ExtractionRule
            .newBuilder()
            .setHost("oliveyoung.co.kr")
            .setFetch(ExtractionRule.FetchMode.RENDER)
            .setAbsentOriginalPrice(ExtractionRule.AbsentOriginalPrice.NO_DISCOUNT)
            .build()

    private class FakeAccessPolicy(
        private val access: DomainAccess?,
        private val blockedDomains: List<String>,
    ) : DomainAccessPolicy {
        override fun accessOf(link: ProductLink): DomainAccess? = access

        override fun authorizedFor(link: ProductLink): Boolean = access == DomainAccess.ALLOWED

        override fun blockedDomains(): List<String> = blockedDomains
    }

    private fun probeWith(
        access: DomainAccess? = null,
        blockedDomains: List<String> = emptyList(),
        identity: String? = null,
        server: (MockRestServiceServer) -> Unit,
    ): HttpExtractionRuleProbe {
        val builder =
            RestClient
                .builder()
                .baseUrl("http://extractor.test")
                .configureMessageConverters { it.registerDefaults().addCustomConverter(RemoteExtractionContract.messageConverter()) }
        val mockServer = MockRestServiceServer.bindTo(builder).build()
        server(mockServer)
        return HttpExtractionRuleProbe(
            builder.build(),
            FakeAccessPolicy(access, blockedDomains),
            object : ExtractionIdentitySettings {
                override fun identityOverride(): String? = identity
            },
        )
    }

    private fun failureMessage(
        status: HttpStatus,
        body: String,
    ): String {
        val probe =
            probeWith { server ->
                server.expect(requestTo(probeUrl)).andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body))
            }
        return assertFailsWith<IllegalArgumentException> { probe.probe(link, rule) }.message.orEmpty()
    }

    @Test
    fun `요청에는 규칙과 실제 추출과 같은 허락·차단·신원이 실리고 응답의 값이 그대로 읽힌다`() {
        val probe =
            probeWith(access = DomainAccess.ALLOWED, blockedDomains = listOf("kream.co.kr"), identity = "PiKi") { server ->
                server
                    .expect(requestTo(probeUrl))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.url").value(link.value.toString()))
                    .andExpect(jsonPath("$.rule.host").value("oliveyoung.co.kr"))
                    .andExpect(jsonPath("$.rule.fetch").value("RENDER"))
                    .andExpect(jsonPath("$.rule.absentOriginalPrice").value("NO_DISCOUNT"))
                    .andExpect(jsonPath("$.authorized").value(true))
                    .andExpect(jsonPath("$.blockedDomains[0]").value("kream.co.kr"))
                    .andExpect(jsonPath("$.identity").value("PiKi"))
                    .andRespond(
                        withSuccess(
                            """
                            {"matched": true,
                             "byRule": {"name": "그린핑거 로션", "currentPrice": 39700},
                             "byCommon": {},
                             "finalUrl": "https://www.oliveyoung.co.kr/store/goods/getGoodsDetail.do?goodsNo=A000000171788",
                             "unreadFields": ["originalPrice"],
                             "noDiscountApplied": true,
                             "futureField": "모르는 필드"}
                            """.trimIndent(),
                            MediaType.APPLICATION_JSON,
                        ),
                    )
            }

        val result = probe.probe(link, rule)

        assertTrue(result.matched)
        assertEquals("그린핑거 로션", result.byRule.name)
        assertEquals(39_700, result.byRule.currentPrice)
        assertNull(result.byRule.originalPrice)
        assertNull(result.byCommon.name)
        assertEquals(listOf("originalPrice"), result.unreadFields)
        assertTrue(result.noDiscountApplied)
        assertNull(result.rejection())
    }

    @Test
    fun `해석 못 한 규칙·대상 아님·버려짐은 저장을 막는 사유가 된다`() {
        fun rejectionOf(body: String): String? =
            probeWith { server -> server.expect(requestTo(probeUrl)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON)) }
                .probe(link, rule)
                .rejection()

        assertEquals(true, rejectionOf("""{"ruleError": "name 의 선택자를 해석하지 못했다"}""")?.contains("name 의 선택자"))
        assertEquals(true, rejectionOf("""{"byCommon": {"name": "상품"}, "finalUrl": "https://m.29cm.co.kr/p/1"}""")?.contains("m.29cm.co.kr"))
        assertEquals(
            true,
            rejectionOf("""{"matched": true, "discarded": true, "unreadFields": ["name", "currentPrice"]}""")?.contains("name, currentPrice"),
        )
    }

    @Test
    fun `페이지를 받지 못하면 extractor 의 code 를 사유에 싣는다`() {
        assertEquals(
            "${HttpExtractionRuleProbe.MESSAGE_PAGE_FAILED_PREFIX} (BLOCKED_DOMAIN)",
            failureMessage(HttpStatus.UNPROCESSABLE_ENTITY, """{"code":"BLOCKED_DOMAIN"}"""),
        )
        assertEquals(
            "${HttpExtractionRuleProbe.MESSAGE_PAGE_FAILED_PREFIX} (HEADLESS_BLOCKED)",
            failureMessage(HttpStatus.BAD_GATEWAY, """{"code":"HEADLESS_BLOCKED"}"""),
        )
    }

    @Test
    fun `code 없는 실패는 400 이면 입력 확인을 그 밖에는 잠시 후 재시도를 안내한다`() {
        assertEquals(HttpExtractionRuleProbe.MESSAGE_BAD_REQUEST, failureMessage(HttpStatus.BAD_REQUEST, "{}"))
        assertEquals(HttpExtractionRuleProbe.MESSAGE_UNAVAILABLE, failureMessage(HttpStatus.BAD_GATEWAY, "{}"))
        assertEquals(HttpExtractionRuleProbe.MESSAGE_UNAVAILABLE, failureMessage(HttpStatus.NOT_FOUND, "not found"))
    }

    @Test
    fun `extractor 에 닿지 못하면 잠시 후 재시도를 안내한다`() {
        val probe =
            probeWith { server ->
                server.expect(requestTo(probeUrl)).andRespond { throw IOException("connection refused") }
            }

        val failure = assertFailsWith<IllegalArgumentException> { probe.probe(link, rule) }

        assertEquals(HttpExtractionRuleProbe.MESSAGE_UNAVAILABLE, failure.message)
    }
}
