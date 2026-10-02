package com.depromeet.piki.product.service.remote

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// extractor 호출 실패. 일시 실패는 RETRYABLE, 확정 실패(422)는 SERVER_ERROR 로 번역함. code 별 행선지는 RemoteExtractionContract
// 소비처가 파서뿐이라 응답 code 로 나가지 않아 공개 카탈로그에 등록하지 않음
class ProductExtractorException private constructor(
    override val errorCode: ErrorCode,
    cause: Throwable? = null,
) : BaseException(errorCode.message, cause),
    HttpMappable {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        // 5xx·타임아웃·연결 실패·빈 응답
        fun transientFailure(cause: Throwable?): ProductExtractorException =
            ProductExtractorException(ProductExtractorErrorCode.TRANSIENT_FAILURE, cause)

        // 원격이 422(확정 실패)로 답했고, 우리 방어가 발동했거나(호스트 차단·리다이렉트 이상) 이 바이너리가
        // 모르는 code 인 경우. tolerant reader — 모르는 code 라도 422 면 확정 실패다(extractor 계약 §1).
        // 재시도 무의미이므로 비 RETRYABLE.
        fun permanentFailure(): ProductExtractorException = ProductExtractorException(ProductExtractorErrorCode.PERMANENT_FAILURE)

        // 원격이 422 로 답했고, 그 사유가 "대상이 우리를 막았다"인 경우(4xx 접근 거부·영구 upstream 거절).
        // 재시도 무의미인 건 같고, 메트릭에서 blocked 로 따로 세어 정책(BLOCKED) 판단의 입력이 된다.
        fun blockedByTarget(): ProductExtractorException = ProductExtractorException(ProductExtractorErrorCode.BLOCKED_BY_TARGET)
    }
}
