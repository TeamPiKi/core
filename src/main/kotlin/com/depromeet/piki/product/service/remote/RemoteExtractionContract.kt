package com.depromeet.piki.product.service.remote

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.contracts.extraction.v1.ExtractionFailure
import com.depromeet.piki.contracts.extraction.v1.ExtractionMethod
import com.depromeet.piki.contracts.extraction.v1.ExtractionResult
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.product.service.ProductSnapshotException
import com.google.protobuf.util.JsonFormat
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.protobuf.ProtobufJsonFormatHttpMessageConverter
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

// 원격 추출 서비스(extractor) 계약(정본: infra contracts/)의 공용 절반.
// link(HttpProductLinkExtractor)·image(HttpImageSnapshotExtractor) 두 클라이언트가 같은 응답 모양(ExtractionResult)과
// 같은 3갈래 번역을 쓰므로 호출·번역 전체를 한 곳에 모은다 — 계약이 진화할 때 두 클라이언트가 조용히 어긋나는 것을 막는다.
// 클라이언트별로 갈리는 건 요청 모양(URL vs bucket·key)과 로그 컨텍스트(target)뿐이다.
//
// 요청·응답 클래스는 계약 정본에서 생성된다 — 이쪽에 필드 정의가 없어 한쪽만 개명되는 사고가 불가능하다.
internal object RemoteExtractionContract {
    private val log = LoggerFactory.getLogger(javaClass)

    // 기본 파서는 모르는 필드에 실패한다 — 그대로 두면 extractor 가 필드를 먼저 더한 2xx 가 일시 실패로 떨어진다.
    // 프린터는 authorized false 를 생략하지 않고 찍어 "무엇을 보냈나"가 와이어와 상대 로그에 남게 한다.
    fun messageConverter(): HttpMessageConverter<*> =
        ProtobufJsonFormatHttpMessageConverter(
            JsonFormat.parser().ignoringUnknownFields(),
            JsonFormat.printer().alwaysPrintFieldsWithNoPresence(),
        ).apply { supportedMediaTypes = listOf(MediaType.APPLICATION_JSON) }

    // 확정 실패(422) code 전수 → 우리 예외. 계약 카탈로그(shared-infra/contracts/extraction-error-codes.yaml)의
    // permanent code 를 빠짐없이 여기에 명시한다 — 표에 없는 code 는 아래 fallback 으로 떨어져 internal_error 로
    // 세지므로, 매핑 누락이 "우리가 이름을 아는 실패"인 척 묻히지 않는다.
    // 표를 when 대신 값으로 둔 이유: 카탈로그와의 전수 대조(ExtractionErrorCatalogTest)가 이 키 집합을 직접 읽는다.
    // when 분기는 밖에서 열거할 수 없어 "누락이 else 로 조용히 흡수됐는지"를 기계가 가릴 수 없다.
    //
    // 어느 예외로 보내는지가 곧 메트릭 reason 이다 — 예외의 errorCode 가 bucket 을 들고 있고(ExtractionFailureCode),
    // ItemParsingMetrics.reasonOf 가 그 bucket 을 라벨로 옮긴다. 문구는 여기서 갈리지 않는다(고정 사용자 문구).
    internal val PERMANENT_TRANSLATIONS: Map<String, () -> BaseException> =
        mapOf(
            // 사용자가 상품 아닌 걸 넣었다 — 정상 트래픽.
            "NOT_PRODUCT_PAGE" to { ProductSnapshotException.notProductPage() },
            "INVALID_URL" to { ProductSnapshotException.notProductPage() },
            // 우리 구성으로 못 읽었다 — 도메인 허가 후보 신호.
            "EMPTY_SHELL" to { ProductSnapshotException.noExtractableContent() },
            "NO_EXTRACTABLE_CONTENT" to { ProductSnapshotException.noExtractableContent() },
            // 대상이 우리를 막았다 — BLOCKED 정책 후보.
            "FETCH_CLIENT_ERROR" to { ProductExtractorException.blockedByTarget() },
            "PERMANENT_UPSTREAM" to { ProductExtractorException.blockedByTarget() },
            // 추출은 됐는데 값을 믿을 수 없다 — 모델·프롬프트·검증 규칙 소관.
            // IMAGE_UNSUPPORTED(이미지 경로 전용)도 "받은 결과를 상품 정보로 쓸 수 없다"는 같은 성격이다.
            "UNTRUSTWORTHY_VALUE" to { ProductSnapshotException.untrustworthyValue() },
            "LLM_INVALID_RESPONSE" to { ProductSnapshotException.untrustworthyValue() },
            "IMAGE_UNSUPPORTED" to { ProductSnapshotException.untrustworthyValue() },
            // 우리 방어가 발동했다 — 정상 흐름이면 애초에 우리 경계(SSRF 가드·리다이렉트 제한)가 먼저 걸렀어야 한다.
            "BLOCKED_HOST" to { ProductExtractorException.permanentFailure() },
            "TOO_MANY_REDIRECTS" to { ProductExtractorException.permanentFailure() },
            "MALFORMED_REDIRECT" to { ProductExtractorException.permanentFailure() },
        )

    // 원격 추출 호출 한 건의 전부 — POST 부터 3갈래(2xx 매핑 / 422+code 확정 / 그 외 일시)가 이 함수 안에서 끝난다.
    // transport catch 까지 여기 두는 이유: 클라이언트별 복제가 남으면 계약이 진화할 때(타임아웃 구분·status 취급 등)
    // 한쪽만 고쳐져 링크·이미지가 조용히 어긋난다 — 클라이언트에는 요청 구성(경로·body)만 남긴다.
    // target 은 로그 컨텍스트다. 마스킹된 값만 넘긴다(url 은 safeLogString, key 는 내부 S3 식별자) — raw URL 금지.
    fun postForSnapshot(
        restClient: RestClient,
        path: String,
        request: Any,
        link: ProductLink?,
        target: String,
    ): ProductSnapshot {
        val response =
            try {
                restClient
                    .post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(ExtractionResult::class.java)
            } catch (e: RestClientResponseException) {
                throw translate(e, target)
            } catch (e: RestClientException) {
                // 연결 실패·read timeout(ResourceAccessException)·본문 추출 중 오류 등 transport 장애 — 일시로 본다.
                throw ProductExtractorException.transientFailure(e)
            }
        return toSnapshot(response, link, target)
    }

    // 2xx 응답 → ProductSnapshot. extractor 는 2xx 로 name·imageUrl·currentPrice 중 **하나 이상**을 보장한다
    // (자기 쪽 ExtractionResponse.from 이 하나도 못 건졌을 때만 422 UNTRUSTWORTHY_VALUE 로 닫는다, extractor#37).
    // 그래서 부분값 2xx 는 계약 위반이 아니라 정상 결과이고, 그것을 READY/INCOMPLETE/FAILED 로 가르는 판정은
    // 경계가 아니라 도메인(ItemSnapshot.markExtracted, #944)이 쥔다 — 여기서 세 필드를 다 요구하면 부분값이
    // INCOMPLETE 로 가는 길 자체가 닫혀 재시도 후 FAILED 로 끝난다(#950 이 고친 것이 정확히 그 상태다).
    // 남은 가드는 "계약이 깨진 2xx" 하나다: 셋 다 빈 응답은 extractor 가 422 로 닫았어야 할 것이라 일시 실패로
    // 걸러 재시도한다 — 원인이 "원격 계약 위반"으로 boundary 로그에 또렷이 남는다.
    // raw 응답 필드를 본다: "값은 있으나 우리가 정규화로 떨구는" 경우(non-https imageUrl 등)는 여기가 아니라
    // fromExtracted 소관이라, 그건 스냅샷에 null 로 흘러 markExtracted 가 최종 판정한다(정규화가 남긴 값이
    // 일부면 INCOMPLETE, 전부 떨구면 FAILED). 현재 extractor 는 결과 URL 을 https 로 하드코딩 생성하므로
    // non-https 2xx 는 구성 불가능하다 — extractor 가 CDN 등 결과 URL 출처를 바꾸면 이 가드를 재검토한다.
    private fun toSnapshot(
        response: ExtractionResult?,
        link: ProductLink?,
        target: String,
    ): ProductSnapshot {
        response ?: throw ProductExtractorException.transientFailure(null)
        if (response.hasNoExtractedValue()) throw contractViolation(target)
        return response.toProductSnapshot(link)
    }

    // 이 응답이 2xx 로 온 것 자체가 계약 위반이다. 판정 기준의 정본은 ItemSnapshot.hasNoExtractedValue 다.
    private fun ExtractionResult.hasNoExtractedValue(): Boolean =
        !(hasName() && name.isNotBlank()) && !hasImageUrl() && !hasCurrentPrice()

    // fromExtracted 를 반드시 경유한다 — 원격이 정상 값을 보장해도 신뢰 경계를 넘어온 값은 다시 검증한다(다층 방어).
    // has* 로 가르는 이유는 JSON 생략과 null 을 같게 읽기 위해서다. 모르는 method 는 UNSPECIFIED 라 기록하지 않는다.
    private fun ExtractionResult.toProductSnapshot(link: ProductLink?): ProductSnapshot =
        ProductSnapshot.fromExtracted(
            link = link,
            name = if (hasName()) name else null,
            imageUrl = if (hasImageUrl()) imageUrl else null,
            price = if (hasCurrentPrice()) currentPrice else null,
            currency = if (hasCurrency()) currency else null,
            finalUrl = if (hasFinalUrl()) finalUrl else null,
            extractionMethod = method.takeIf { it != ExtractionMethod.EXTRACTION_METHOD_UNSPECIFIED }?.name,
        )

    private fun contractViolation(target: String): ProductExtractorException {
        log.warn("remote extract contract violation: no extracted value in 2xx {}", target)
        return ProductExtractorException.transientFailure(null)
    }

    // 계약 3갈래 번역: 422 만 확정 실패, 그 외 status 는 전부 일시(fail-safe — recover 상한이 재시도를 바운드).
    // **전이 판정은 여전히 status 만 본다** — code 는 그 확정 실패를 무엇이라 부르고 어떻게 셀지(bucket)만 가른다.
    // 그래서 모르는 code 도 확정 실패라는 결론은 같고(tolerant reader, 계약 §1), 다만 internal_error 로 세어
    // "매핑이 뒤처졌다"가 지표에 드러난다.
    private fun translate(
        e: RestClientResponseException,
        target: String,
    ): BaseException {
        if (!e.statusCode.isSameCodeAs(HttpStatus.UNPROCESSABLE_ENTITY)) {
            // 실제 원격 status(401·404·5xx 등)를 여기서 남긴다 — 예외는 category 만 들고 httpStatus 는 항상 502 라,
            // 워커의 재시도 warn 로그엔 실제 원격 status 가 드러나지 않는다(그럼 잘못된 base-url 404·인증 401 을 502 로 오인).
            log.warn("remote extract transient status={} {}", e.statusCode.value(), target)
            return ProductExtractorException.transientFailure(e)
        }
        // 모르는 code 문자열은 파서가 무시해 UNSPECIFIED 로 읽힌다 — 그 이름은 표에 없으니 아래 fallback 으로 간다.
        val code =
            runCatching { e.getResponseBodyAs(ExtractionFailure::class.java)?.code?.name }
                .getOrNull()
        // code 는 관측·디버깅용(계약 §1) — 응답엔 노출되지 않고 로그로만 남긴다. 확정 실패는 계약상 정상 결과라 info.
        // 원문 code 는 이 줄에만 남는다: 메트릭은 bucket 단위라(카디널리티) 개별 code 추적은 로그가 진다.
        log.info("remote extract permanent code={} {}", code, target)
        // 표에 없는 code — 이 바이너리보다 새 extractor 가 사유를 늘렸거나 body 가 깨진 경우. 확정 실패인 건 같다.
        val translation = PERMANENT_TRANSLATIONS[code] ?: return ProductExtractorException.permanentFailure()
        return translation()
    }
}
