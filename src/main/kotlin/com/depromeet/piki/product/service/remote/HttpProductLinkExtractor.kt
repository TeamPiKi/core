package com.depromeet.piki.product.service.remote

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.product.service.ProductSnapshotException
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

// 원격 추출 서비스(PIKI-Extractor) 호출 클라이언트. 계약은 extractor repo 의 docs/api-contract.md 가 single source:
//   2xx + 결과   → ProductSnapshot (검증·정규화는 아래 toProductSnapshot 이 fromExtracted 로 수행)
//   422 + {code} → 확정 실패 — 비 RETRYABLE 예외로 번역 → 워커 즉시 FAILED
//   그 외 전부   → 일시 실패 — RETRYABLE 예외 → PROCESSING 유지 후 recover 재시도 (attempt 상한이 바운드)
//
// ProductLinkExtractor 를 구현하지 않는다 — 진입점(ProductLinkExtractor) 주입 후보가 둘(Fallback + 이 빈)이 되는
// 모호성을 피한다. 호출자는 RoutingProductLinkExtractor 뿐이며, 원격 서브시스템과 함께 같은 키로 뜨고 꺼진다.
@Component
@ConditionalOnProperty(prefix = "product.extract.remote", name = ["enabled"], havingValue = "true")
class HttpProductLinkExtractor(
    @Qualifier("remoteExtractionRestClient") private val restClient: RestClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun extract(link: ProductLink): ProductSnapshot {
        val response =
            try {
                restClient
                    .post()
                    .uri(LINK_EXTRACTION_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(RemoteLinkExtractionRequest(link.value.toString()))
                    .retrieve()
                    .body(RemoteLinkExtractionResponse::class.java)
            } catch (e: RestClientResponseException) {
                throw translate(link, e)
            } catch (e: RestClientException) {
                // 연결 실패·read timeout(ResourceAccessException)·본문 추출 중 오류 등 transport 장애 — 일시로 본다.
                throw ProductExtractorException.transientFailure(e)
            } ?: throw ProductExtractorException.transientFailure(null)
        return response.toProductSnapshot(link)
    }

    // 계약 3갈래 번역: 422 만 확정 실패, 그 외 status 는 전부 일시(fail-safe — recover 상한이 재시도를 바운드).
    // NOT_PRODUCT_PAGE·UNTRUSTWORTHY_VALUE 는 기존 ProductSnapshotException 으로 되돌려, 워커 메트릭
    // (item.parsing reason=not_product)과 실패 의미가 embedded 경로와 동일하게 유지되도록 한다.
    private fun translate(
        link: ProductLink,
        e: RestClientResponseException,
    ): BaseException {
        if (!e.statusCode.isSameCodeAs(HttpStatus.UNPROCESSABLE_ENTITY)) {
            return ProductExtractorException.transientFailure(e)
        }
        val code =
            runCatching { e.getResponseBodyAs(RemoteExtractionFailureResponse::class.java)?.code }
                .getOrNull()
        // code 는 관측·디버깅용(계약 §1) — 응답엔 노출되지 않고 로그로만 남긴다. 확정 실패는 계약상 정상 결과라 info.
        log.info("remote extract permanent code={} url={}", code, link.safeLogString())
        return when (code) {
            CODE_NOT_PRODUCT_PAGE -> ProductSnapshotException.notProductPage()
            CODE_UNTRUSTWORTHY_VALUE -> ProductSnapshotException.untrustworthyValue()
            else -> ProductExtractorException.permanentFailure()
        }
    }

    companion object {
        private const val LINK_EXTRACTION_PATH = "/internal/extractions/link"
        private const val CODE_NOT_PRODUCT_PAGE = "NOT_PRODUCT_PAGE"
        private const val CODE_UNTRUSTWORTHY_VALUE = "UNTRUSTWORTHY_VALUE"
    }
}

// wire 모델 — 이 클라이언트 밖에서 쓰지 않는다(file-private).
private data class RemoteLinkExtractionRequest(
    val url: String,
)

// tolerant reader(계약 §4): extractor 가 additive 로 필드를 더해도 모르는 필드는 무시한다.
@JsonIgnoreProperties(ignoreUnknown = true)
private data class RemoteLinkExtractionResponse(
    val name: String? = null,
    val imageUrl: String? = null,
    val currentPrice: Int? = null,
    val currency: String? = null,
) {
    // 외부 응답 → 도메인 매핑은 DTO 자신이 진다 (CLAUDE.md, GeminiExtractionResult.toProductSnapshot 과 같은 패턴).
    // fromExtracted 를 반드시 경유한다 — https-only imageUrl(XSS 사다리 차단)·currency ISO 정규화·범위 검증은
    // 모든 추출 경로가 공유하는 단일 진실 원천이고, 원격 계약이 정상 값을 보장하더라도 신뢰 경계(외부 서비스)를
    // 넘어온 값은 우리 경계에서 다시 검증한다(다층 방어). 범위 위반은 embedded 의 LLM 경로와 동일하게
    // untrustworthyValue(→ 워커 reason=not_product)로 떨어진다.
    fun toProductSnapshot(link: ProductLink): ProductSnapshot =
        ProductSnapshot.fromExtracted(
            link = link,
            name = name,
            imageUrl = imageUrl,
            currentPrice = currentPrice,
            currency = currency,
        )
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class RemoteExtractionFailureResponse(
    val code: String? = null,
)
