package com.depromeet.piki.product.service.remote

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.product.service.ExtractionFailureBucket
import com.depromeet.piki.product.service.ExtractionFailureCode

// ProductExtractorException 의 code 배정표(에픽 #728). 번호는 append-only — 재배치·결번 침범 금지.
// 소비처가 파서뿐이라 응답 code 로 나가지 않아 ErrorCodeRegistry 에 등록하지 않음
// 세 사유가 message 를 공유함. 원격 실패 이유는 사용자 관심사가 아니고 구분은 category·bucket·로그가 맡음
// 일시 실패(TRANSIENT_FAILURE)는 종결 사유가 아니라 bucket 없음
enum class ProductExtractorErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
    override val bucket: ExtractionFailureBucket?,
) : ExtractionFailureCode {
    // 원격 호출이 일시적으로 실패(5xx·타임아웃·연결 실패·빈 응답·2xx 계약 위반).
    TRANSIENT_FAILURE("EXTRACTOR-001", ErrorCategory.RETRYABLE, "상품 정보를 가져오지 못했어요.", null),

    // 우리 방어가 발동했거나(호스트 차단·리다이렉트 이상) 이 바이너리가 모르는 code 로 422 가 온 경우.
    // tolerant reader — 모르는 code 라도 422 면 확정 실패다(extractor 계약 §1). 재시도 무의미.
    // 둘 다 "코드를 조사한다"가 대응이라 internal_error 로 센다: 전자는 우리 방어·버그이고, 후자는 매핑이
    // 뒤처졌다는 신호(카탈로그·translate 갱신)라 결국 코드 작업으로 귀결된다.
    PERMANENT_FAILURE(
        "EXTRACTOR-002",
        ErrorCategory.SERVER_ERROR,
        "상품 정보를 가져오지 못했어요.",
        ExtractionFailureBucket.INTERNAL_ERROR,
    ),

    // 대상이 우리를 막아 확정 실패. 우리 버그도 사용자 잘못도 아니라 따로 센다 — 늘면 그 도메인의
    // BLOCKED 정책(백오피스) 후보가 된다.
    BLOCKED_BY_TARGET(
        "EXTRACTOR-003",
        ErrorCategory.SERVER_ERROR,
        "상품 정보를 가져오지 못했어요.",
        ExtractionFailureBucket.BLOCKED,
    ),
}
