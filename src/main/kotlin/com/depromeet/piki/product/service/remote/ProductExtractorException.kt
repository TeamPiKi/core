package com.depromeet.piki.product.service.remote

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// 원격 추출기(extractor, 도메인 용어 product 의 ProductExtractor) 호출 실패. 워커(AsyncItemParsingWorker.isRetryable)의
// 재시도 판정이 category 만 보므로, extractor 계약의 3갈래 중 "일시(그 외 전부)"는 RETRYABLE 로, "확정(422)"는 SERVER_ERROR 로 번역한다.
// (확정 실패라도 "이 링크로는 상품 스냅샷을 만들 수 없다"는 사유 — 상품 아님·못 읽음·값 불신 — 는 이 예외가
// 아니라 ProductSnapshotException 으로 되돌린다. 어느 code 가 어디로 가는지는 RemoteExtractionContract 참고.)
// message·category·httpStatus 는 전부 errorCode 하나에서 파생한다(ProductExtractorErrorCode 가 single source).
// errorCode 는 클래스 모양 통일 목적이며, 비동기 워커 전용이라 공개 카탈로그에 등록하지 않는다.
class ProductExtractorException private constructor(
    override val errorCode: ErrorCode,
    cause: Throwable? = null,
) : BaseException(errorCode.message, cause),
    HttpMappable {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        // 원격 호출이 일시적으로 실패(5xx·타임아웃·연결 실패·빈 응답). 워커가 소유권을 반납(PROCESSING→PENDING) → 다음 tick 이 다시 집는다.
        fun transientFailure(cause: Throwable?): ProductExtractorException =
            ProductExtractorException(ProductExtractorErrorCode.TRANSIENT_FAILURE, cause)

        // 원격이 422(확정 실패)로 답했고, 우리 방어가 발동했거나(호스트 차단·리다이렉트 이상) 이 바이너리가
        // 모르는 code 인 경우. tolerant reader — 모르는 code 라도 422 면 확정 실패다(extractor 계약 §1).
        // 재시도 무의미이므로 비 RETRYABLE.
        fun permanentFailure(): ProductExtractorException = ProductExtractorException(ProductExtractorErrorCode.PERMANENT_FAILURE)

        // 원격이 422 로 답했고, 그 사유가 "대상이 우리를 막았다"인 경우(4xx 접근 거부·영구 upstream 거절).
        // 재시도 무의미인 건 같고, 메트릭에서 blocked 로 따로 세어 정책(UNSUPPORTED) 판단의 입력이 된다.
        fun blockedByTarget(): ProductExtractorException = ProductExtractorException(ProductExtractorErrorCode.BLOCKED_BY_TARGET)
    }
}
