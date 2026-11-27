package com.depromeet.piki.product.service.remote

import com.depromeet.piki.contracts.extraction.v1.ExtractionErrorCode
import com.depromeet.piki.contracts.extraction.v1.ExtractionFailure
import com.depromeet.piki.contracts.extraction.v1.ExtractionRule
import com.depromeet.piki.contracts.extraction.v1.RuleProbeRequest
import com.depromeet.piki.contracts.extraction.v1.RuleProbeResult
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.routing.DomainAccessPolicy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

// 몰별 추출 규칙의 시험 추출. 백오피스가 규칙을 저장하기 전에 extractor 로 실제 상품 페이지에 적용해 본다
// (계약: shared-infra/contracts/extraction.proto 의 rpc RuleProbe, 동작 규칙은 TeamPiKi/infra 의 extraction-api.md).
//
// 선택자가 맞는지는 페이지를 읽어 봐야 안다. 규칙은 공통 규칙보다 먼저 적용되므로 틀린 선택자는 틀린 가격을 그대로
// 저장하게 만든다 — 그래서 저장 전에 무엇을 읽는지 사람이 본다.
//
// 인터페이스로 두는 이유는 ExtractionModelProbe 와 같다(외부 호출 경계).
interface ExtractionRuleProbe {
    // 페이지를 받지 못했거나 extractor 에 닿지 못하면 화면에 그대로 띄울 사유를 담아 던진다
    // (백오피스 컨트롤러가 IllegalArgumentException 을 화면 에러로 흡수하는 기존 관례).
    fun probe(
        link: ProductLink,
        rule: ExtractionRule,
    ): ExtractionRuleProbeResult
}

// extractor 가 한 층에서 읽은 값. 못 읽은 필드는 null.
data class ExtractionRuleReading(
    val name: String?,
    val imageUrl: String?,
    val currentPrice: Int?,
    val originalPrice: Int?,
) {
    fun isEmpty(): Boolean = listOfNotNull(name, imageUrl, currentPrice, originalPrice).isEmpty()

    companion object {
        fun from(reading: RuleProbeResult.Reading): ExtractionRuleReading =
            ExtractionRuleReading(
                name = if (reading.hasName()) reading.name else null,
                imageUrl = if (reading.hasImageUrl()) reading.imageUrl else null,
                currentPrice = if (reading.hasCurrentPrice()) reading.currentPrice else null,
                originalPrice = if (reading.hasOriginalPrice()) reading.originalPrice else null,
            )
    }
}

data class ExtractionRuleProbeResult(
    val matched: Boolean,
    val byRule: ExtractionRuleReading,
    val byCommon: ExtractionRuleReading,
    val ruleError: String?,
    val finalUrl: String?,
    val unreadFields: List<String>,
    val discarded: Boolean,
    val noDiscountApplied: Boolean,
) {
    // 두 층이 모두 빈손이면 선택자보다 페이지 쪽 문제다. 몰이 요청을 막아도 extractor 는 200 을 준다(dev 에서 올리브영으로 재현)
    private val blockedHint: String get() = if (byRule.isEmpty() && byCommon.isEmpty()) " $MESSAGE_MAYBE_BLOCKED" else ""

    // 이 규칙을 켠 채 저장하면 안 되는 이유. 없으면 null.
    // 셋 다 "저장해도 이 페이지에서는 적용되지 않는다" 는 뜻이라, 통과시키면 켜 두고 안 도는 규칙이 남는다.
    fun rejection(): String? {
        ruleError?.let { return "extractor 가 규칙을 해석하지 못했습니다: $it" }
        if (!matched) return "이 상품 주소는 규칙의 대상이 아닙니다 (최종 주소: ${finalUrl ?: "알 수 없음"}).$blockedHint"
        if (discarded) return "출처를 적은 필드를 읽지 못해 규칙이 버려집니다: ${unreadFields.joinToString(", ")}.$blockedHint"
        return null
    }

    companion object {
        internal const val MESSAGE_MAYBE_BLOCKED = "공통 규칙도 값을 읽지 못했습니다. 몰이 요청을 막았을 수 있으니 한 번 더 시험해 보세요."

        fun from(result: RuleProbeResult): ExtractionRuleProbeResult =
            ExtractionRuleProbeResult(
                matched = result.matched,
                byRule = ExtractionRuleReading.from(result.byRule),
                byCommon = ExtractionRuleReading.from(result.byCommon),
                ruleError = if (result.hasRuleError()) result.ruleError else null,
                finalUrl = if (result.hasFinalUrl()) result.finalUrl else null,
                unreadFields = result.unreadFieldsList.toList(),
                discarded = result.discarded,
                noDiscountApplied = result.noDiscountApplied,
            )
    }
}

@Component
class HttpExtractionRuleProbe(
    @Qualifier("remoteExtractionRestClient") private val restClient: RestClient,
    private val accessPolicy: DomainAccessPolicy,
    private val identitySettings: ExtractionIdentitySettings,
) : ExtractionRuleProbe {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun probe(
        link: ProductLink,
        rule: ExtractionRule,
    ): ExtractionRuleProbeResult {
        // 실제 추출과 같은 허락·차단·신원으로 페이지를 받는다. 다르면 시험에서는 읽히고 실제로는 막히는 규칙이 저장된다
        val request =
            RuleProbeRequest
                .newBuilder()
                .setUrl(link.value.toString())
                .setRule(rule)
                .setAuthorized(accessPolicy.authorizedFor(link))
                .addAllBlockedDomains(accessPolicy.blockedDomains())
        identitySettings.identityOverride()?.let(request::setIdentity)
        val response =
            try {
                restClient
                    .post()
                    .uri(ExtractionEndpoints.RULE_PROBE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request.build())
                    .retrieve()
                    .body(RuleProbeResult::class.java)
            } catch (e: RestClientResponseException) {
                throw translate(e, link)
            } catch (e: RestClientException) {
                log.warn("규칙 시험 추출 transport 실패: url=\"{}\"", link, e)
                throw IllegalArgumentException(MESSAGE_UNAVAILABLE)
            }
        response ?: throw IllegalArgumentException(MESSAGE_UNAVAILABLE)
        return ExtractionRuleProbeResult.from(response)
    }

    // 페이지를 받지 못한 경우는 추출 경로와 같은 실패 응답이 온다. 화면에는 code 를 그대로 보여 준다 —
    // 운영자가 "차단인가, 주소가 틀렸나" 를 가르는 근거가 code 뿐이다.
    private fun translate(
        e: RestClientResponseException,
        link: ProductLink,
    ): IllegalArgumentException {
        val code =
            runCatching { e.getResponseBodyAs(ExtractionFailure::class.java)?.code }
                .getOrNull()
                ?.takeUnless { it in UNNAMED_CODES }
        log.info("규칙 시험 추출 실패 status={} code={} url=\"{}\"", e.statusCode.value(), code, link)
        code ?: return IllegalArgumentException(
            if (e.statusCode.isSameCodeAs(HttpStatus.BAD_REQUEST)) MESSAGE_BAD_REQUEST else MESSAGE_UNAVAILABLE,
        )
        return IllegalArgumentException("$MESSAGE_PAGE_FAILED_PREFIX ($code)")
    }

    companion object {
        private val UNNAMED_CODES =
            setOf(ExtractionErrorCode.EXTRACTION_ERROR_CODE_UNSPECIFIED, ExtractionErrorCode.UNRECOGNIZED)

        internal const val MESSAGE_UNAVAILABLE = "지금은 시험 추출을 할 수 없습니다. 잠시 후 다시 시도해 주세요."
        internal const val MESSAGE_BAD_REQUEST = "extractor 가 요청을 받지 않았습니다. 상품 주소와 규칙을 확인해 주세요."
        internal const val MESSAGE_PAGE_FAILED_PREFIX = "상품 페이지를 받지 못했습니다"
    }
}
