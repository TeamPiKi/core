package com.depromeet.piki.product.service.remote

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.contracts.extraction.v1.ExtractionErrorCode
import com.depromeet.piki.contracts.extraction.v1.ExtractionFailure
import com.depromeet.piki.contracts.extraction.v1.ExtractionResult
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

internal object RemoteExtractionContract {
    private val log = LoggerFactory.getLogger(javaClass)

    // 기본 파서는 모르는 필드에 실패해 extractor 가 필드를 먼저 더하면 2xx 가 일시 실패로 떨어진다. 프린터는 authorized false 를 생략하지 않는다
    fun messageConverter(): HttpMessageConverter<*> =
        ProtobufJsonFormatHttpMessageConverter(
            JsonFormat.parser().ignoringUnknownFields(),
            JsonFormat.printer().alwaysPrintFieldsWithNoPresence(),
        ).apply { supportedMediaTypes = listOf(MediaType.APPLICATION_JSON) }

    // when 이 아니라 Map 인 이유: ExtractionErrorCatalogTest 가 키 집합을 열거해 계약과 대조한다. 표에 없는 code 는 internal_error 로 센다
    // 받은 페이지를 보고 내린 판정(상품 아님·빈 셸·4xx·값 불신)은 계약이 일시로 두어 여기 오지 않는다
    internal val PERMANENT_TRANSLATIONS: Map<ExtractionErrorCode, () -> BaseException> =
        mapOf(
            ExtractionErrorCode.INVALID_URL to { ProductSnapshotException.notProductPage() },
            ExtractionErrorCode.IMAGE_UNSUPPORTED to { ProductSnapshotException.untrustworthyValue() },
            ExtractionErrorCode.BLOCKED_HOST to { ProductExtractorException.permanentFailure() },
        )

    // target 에 URL 을 실을 때는 호출자가 따옴표로 감싼다(logfmt)
    fun postForSnapshot(
        restClient: RestClient,
        path: String,
        request: Any,
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
                throw ProductExtractorException.transientFailure(e)
            }
        return toSnapshot(response, target)
    }

    // extractor 는 2xx 에 name·imageUrl·currentPrice 중 하나 이상을 보장한다(extractor#37). 셋 다 비면 계약 위반이라 일시 실패로 재시도한다
    private fun toSnapshot(
        response: ExtractionResult?,
        target: String,
    ): ProductSnapshot {
        response ?: throw ProductExtractorException.transientFailure(null)
        if (response.hasNoExtractedValue()) throw contractViolation(target)
        return response.toProductSnapshot()
    }

    private fun ExtractionResult.hasNoExtractedValue(): Boolean =
        !(hasName() && name.isNotBlank()) && !hasImageUrl() && !hasCurrentPrice()

    // has* 는 JSON 생략과 null 을 같게 읽는다
    private fun ExtractionResult.toProductSnapshot(): ProductSnapshot =
        ProductSnapshot.fromExtracted(
            name = if (hasName()) name else null,
            imageUrl = if (hasImageUrl()) imageUrl else null,
            price = if (hasCurrentPrice()) currentPrice else null,
            currency = if (hasCurrency()) currency else null,
            finalUrl = if (hasFinalUrl()) finalUrl else null,
            method = method,
        )

    private fun contractViolation(target: String): ProductExtractorException {
        log.warn("remote extract contract violation: no extracted value in 2xx {}", target)
        return ProductExtractorException.transientFailure(null)
    }

    private fun translate(
        e: RestClientResponseException,
        target: String,
    ): BaseException {
        if (!e.statusCode.isSameCodeAs(HttpStatus.UNPROCESSABLE_ENTITY)) {
            log.warn("remote extract transient status={} {}", e.statusCode.value(), target)
            return ProductExtractorException.transientFailure(e)
        }
        // 모르는 code 문자열은 파서가 UNSPECIFIED 로 읽어 표에서 빠진다
        val code =
            runCatching { e.getResponseBodyAs(ExtractionFailure::class.java)?.code }
                .getOrNull()
        log.info("remote extract permanent code={} {}", code, target)
        val translation = PERMANENT_TRANSLATIONS[code] ?: return ProductExtractorException.permanentFailure()
        return translation()
    }
}
