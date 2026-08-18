package com.depromeet.piki.product.service.remote

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.product.service.ExtractionFailureBucket
import com.depromeet.piki.product.service.ExtractionFailureCode

// ProductExtractorException 의 code 배정표(에픽 #728). 번호는 append-only — 재배치·결번 침범 금지.
//
// ⚠️ 이 enum 은 ErrorCodeRegistry.all 에 **의도적으로 등록하지 않는다**(ProductSnapshotErrorCode 와 같은 이유).
// 생성 경로인 RemoteExtractionContract 의 유일한 소비자가 비동기 파싱 워커라, GlobalExceptionHandler 를 거치지
// 않고 워커의 재시도 판정(isRetryable)·item FAILED 전이로만 관측된다. 클라 대면 공개 카탈로그 대상이 아니다.
//
// 세 사유가 같은 message 를 공유한다 — 원격이 왜 실패했는지는 사용자 관심사가 아니고, 구분은 category·bucket·로그가 진다.
// 재시도 여부만 갈린다: TRANSIENT_FAILURE 는 RETRYABLE(워커가 소유권을 반납해 다음 tick 이 재실행),
// 나머지는 비 RETRYABLE(즉시 FAILED).
//
// bucket 은 확정 실패를 메트릭에서 무엇으로 셀지의 정본이다(#936). 일시(TRANSIENT_FAILURE)는 종결 집계에
// 닿지 않으므로 bucket 이 없다(카탈로그의 transient code 와 같은 모양).
//
// status 교정: 종전엔 두 팩토리 모두 502 를 직접 들었으나, category 가 status 를 소유하게 되며
// PERMANENT_FAILURE(SERVER_ERROR)는 500 으로 파생된다(에픽 결정 2 의 OAuthException.misconfigured 502→500 과 동형).
// 이 예외는 응답으로 나가지 않아 wire 상 변화가 없고, 워커는 category 만 보므로 재시도 판정도 그대로다.
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
