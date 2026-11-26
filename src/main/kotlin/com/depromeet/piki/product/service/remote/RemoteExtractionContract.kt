package com.depromeet.piki.product.service.remote

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.contracts.extraction.v1.ExtractionErrorCode
import com.depromeet.piki.product.service.ProductSnapshotException
import com.google.protobuf.util.JsonFormat
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.protobuf.ProtobufJsonFormatHttpMessageConverter

internal object RemoteExtractionContract {
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
            ExtractionErrorCode.BLOCKED_DOMAIN to { ProductExtractorException.blockedDomain() },
        )
}
