package com.depromeet.piki.product.service.remote

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// 원격 추출 서비스(PIKI-Extractor) 호출 실패. 워커(AsyncItemParsingWorker.isRetryable)의 재시도 판정이
// category 만 보므로, extractor 계약의 3갈래 중 "일시(그 외 전부)"는 RETRYABLE 로, "확정(422)"는 SERVER_ERROR 로 번역한다.
// (NOT_PRODUCT_PAGE·UNTRUSTWORTHY_VALUE 는 이 예외가 아니라 기존 ProductSnapshotException 으로 되돌려
// 워커 메트릭 reason=not_product 의 의미를 보존한다 — HttpProductLinkExtractor.translate 참고.)
class RemoteExtractionException private constructor(
    message: String,
    override val category: ErrorCategory,
    override val httpStatus: HttpStatus,
    cause: Throwable? = null,
) : BaseException(message, cause),
    HttpMappable {
    companion object {
        // 원격 호출이 일시적으로 실패(5xx·타임아웃·연결 실패·빈 응답). 워커가 PROCESSING 유지 → recover 재시도.
        fun transientFailure(cause: Throwable?): RemoteExtractionException =
            RemoteExtractionException(
                "상품 정보를 가져오지 못했어요.",
                ErrorCategory.RETRYABLE,
                HttpStatus.BAD_GATEWAY,
                cause,
            )

        // 원격이 422(확정 실패)로 답했고, code 가 별도 의미 매핑 대상이 아닌 경우. tolerant reader —
        // 모르는 code 라도 422 면 확정 실패다(extractor 계약 §1). 재시도 무의미이므로 비 RETRYABLE.
        fun permanentFailure(): RemoteExtractionException =
            RemoteExtractionException(
                "상품 정보를 가져오지 못했어요.",
                ErrorCategory.SERVER_ERROR,
                HttpStatus.BAD_GATEWAY,
            )
    }
}
