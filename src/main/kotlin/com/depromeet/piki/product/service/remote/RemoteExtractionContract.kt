package com.depromeet.piki.product.service.remote

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.product.service.ProductSnapshotException
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClientResponseException

// 원격 추출 서비스(PIKI-Extractor) 계약(extractor repo docs/api-contract.md)의 공용 절반.
// link(HttpProductLinkExtractor)·image(HttpImageSnapshotExtractor) 두 클라이언트가 같은 응답 모양(ExtractionResponse)과
// 같은 3갈래 번역을 쓰므로 한 곳에 모은다 — 계약이 진화할 때 두 클라이언트가 조용히 어긋나는 것을 막는다.
// 클라이언트별로 갈리는 건 요청 모양(URL vs bucket·key)과 로그 컨텍스트(target: "url=..." / "key=...")뿐이다.
internal object RemoteExtractionContract {
    private val log = LoggerFactory.getLogger(javaClass)

    private const val CODE_NOT_PRODUCT_PAGE = "NOT_PRODUCT_PAGE"
    private const val CODE_UNTRUSTWORTHY_VALUE = "UNTRUSTWORTHY_VALUE"

    // 2xx 응답 → ProductSnapshot. extractor 는 2xx 로 name·imageUrl·currentPrice 를 non-null 로 보장한다
    // (자기 쪽 ExtractionResponse.from 이 강제). 신뢰 경계를 넘어온 값이라, 계약이 깨진 2xx(초기 이관기 extractor
    // 버그 등: raw 필드가 null)를 여기서 일시 실패로 걸러 불완전 스냅샷이 조용히 READY 로 새는 걸 막는다 —
    // 원인이 "원격 계약 위반"으로 boundary 로그에 또렷이 남는다.
    // raw 응답 필드를 본다: "값은 있으나 우리가 정규화로 떨구는" 경우(non-https imageUrl 등)는 여기가 아니라
    // fromExtracted 소관이라, 그건 embedded 와 동일하게 스냅샷에 null 로 흘러 엔티티 requireReadyInvariant 가 최종 판정한다.
    fun toSnapshot(
        response: RemoteExtractionResponse?,
        link: ProductLink?,
        target: String,
    ): ProductSnapshot {
        response ?: throw ProductExtractorException.transientFailure(null)
        response.name?.takeIf { it.isNotBlank() } ?: throw contractViolation(target)
        response.imageUrl ?: throw contractViolation(target)
        response.currentPrice ?: throw contractViolation(target)
        return response.toProductSnapshot(link)
    }

    private fun contractViolation(target: String): ProductExtractorException {
        log.warn("remote extract contract violation: missing required field {}", target)
        return ProductExtractorException.transientFailure(null)
    }

    // 계약 3갈래 번역: 422 만 확정 실패, 그 외 status 는 전부 일시(fail-safe — recover 상한이 재시도를 바운드).
    // NOT_PRODUCT_PAGE·UNTRUSTWORTHY_VALUE 는 기존 ProductSnapshotException 으로 되돌려, 워커 메트릭
    // (item.parsing reason=not_product)과 실패 의미가 embedded 경로와 동일하게 유지되도록 한다. 그 외 code
    // (이미지 전용 IMAGE_UNSUPPORTED 포함)는 모르는 code 와 같은 취급이다 — 전이 판정은 status 만으로 충분하고
    // code 는 관측용이라(계약 §1), 새 code 마다 매핑을 늘리지 않는다.
    fun translate(
        e: RestClientResponseException,
        target: String,
    ): BaseException {
        if (!e.statusCode.isSameCodeAs(HttpStatus.UNPROCESSABLE_ENTITY)) {
            // 실제 원격 status(401·404·5xx 등)를 여기서 남긴다 — 예외는 category 만 들고 httpStatus 는 항상 502 라,
            // 워커의 재시도 warn 로그엔 실제 원격 status 가 드러나지 않는다(그럼 잘못된 base-url 404·인증 401 을 502 로 오인).
            log.warn("remote extract transient status={} {}", e.statusCode.value(), target)
            return ProductExtractorException.transientFailure(e)
        }
        val code =
            runCatching { e.getResponseBodyAs(RemoteExtractionFailureResponse::class.java)?.code }
                .getOrNull()
        // code 는 관측·디버깅용(계약 §1) — 응답엔 노출되지 않고 로그로만 남긴다. 확정 실패는 계약상 정상 결과라 info.
        log.info("remote extract permanent code={} {}", code, target)
        return when (code) {
            CODE_NOT_PRODUCT_PAGE -> ProductSnapshotException.notProductPage()
            CODE_UNTRUSTWORTHY_VALUE -> ProductSnapshotException.untrustworthyValue()
            else -> ProductExtractorException.permanentFailure()
        }
    }
}

// tolerant reader(계약 §4): extractor 가 additive 로 필드를 더해도 모르는 필드는 무시한다.
// link·image 두 경로가 응답 모양을 공유한다(extractor 쪽도 ExtractionResponse 하나를 공유).
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class RemoteExtractionResponse(
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
    // link 는 이미지 추출엔 원본 URL 이 없어 null 이다 — embedded 이미지 경로(GeminiImageResult)와 같은 계약.
    fun toProductSnapshot(link: ProductLink?): ProductSnapshot =
        ProductSnapshot.fromExtracted(
            link = link,
            name = name,
            imageUrl = imageUrl,
            currentPrice = currentPrice,
            currency = currency,
        )
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class RemoteExtractionFailureResponse(
    val code: String? = null,
)
