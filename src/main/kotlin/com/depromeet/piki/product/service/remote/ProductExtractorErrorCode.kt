package com.depromeet.piki.product.service.remote

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode

// ProductExtractorException 의 code 배정표(에픽 #728). 번호는 append-only — 재배치·결번 침범 금지.
//
// ⚠️ 이 enum 은 ErrorCodeRegistry.all 에 **의도적으로 등록하지 않는다**(ProductSnapshotErrorCode 와 같은 이유).
// 생성 경로인 RemoteExtractionContract 의 유일한 소비자가 비동기 파싱 워커라, GlobalExceptionHandler 를 거치지
// 않고 워커의 재시도 판정(isRetryable)·item FAILED 전이로만 관측된다. 클라 대면 공개 카탈로그 대상이 아니다.
//
// 두 사유가 같은 message 를 공유한다 — 원격이 왜 실패했는지는 사용자 관심사가 아니고, 구분은 category·로그가 진다.
// 재시도 여부만 갈린다: TRANSIENT_FAILURE 는 RETRYABLE(워커가 PROCESSING 유지 후 recover 재시도),
// PERMANENT_FAILURE 는 비 RETRYABLE(즉시 FAILED).
//
// status 교정: 종전엔 두 팩토리 모두 502 를 직접 들었으나, category 가 status 를 소유하게 되며
// PERMANENT_FAILURE(SERVER_ERROR)는 500 으로 파생된다(에픽 결정 2 의 OAuthException.misconfigured 502→500 과 동형).
// 이 예외는 응답으로 나가지 않아 wire 상 변화가 없고, 워커는 category 만 보므로 재시도 판정도 그대로다.
enum class ProductExtractorErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
) : ErrorCode {
    // 원격 호출이 일시적으로 실패(5xx·타임아웃·연결 실패·빈 응답·2xx 계약 위반).
    TRANSIENT_FAILURE("EXTRACTOR-001", ErrorCategory.RETRYABLE, "상품 정보를 가져오지 못했어요."),

    // 원격이 422(확정 실패)로 답했고, code 가 별도 의미 매핑 대상이 아닌 경우. tolerant reader —
    // 모르는 code 라도 422 면 확정 실패다(extractor 계약 §1). 재시도 무의미.
    PERMANENT_FAILURE("EXTRACTOR-002", ErrorCategory.SERVER_ERROR, "상품 정보를 가져오지 못했어요."),
}
