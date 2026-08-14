package com.depromeet.piki.product.service

import com.depromeet.piki.common.exception.ErrorCode

// 확정 실패(원격 422)를 **운영 액션 축**으로 나눈 분류(#936). "이 숫자가 늘면 누가 무엇을 하는가"가 기준이라,
// 실패의 기술적 원인이 아니라 대응이 같은 것끼리 묶인다.
// 계약 카탈로그(shared-infra/contracts/extraction-error-codes.yaml)의 bucket 과 1:1 이고, 파싱 메트릭의
// reason 라벨(ItemParsingMetrics)도 여기서 파생한다 — 셋(카탈로그·예외·메트릭)이 어긋나면
// ExtractionErrorCatalogTest 가 잡는다.
enum class ExtractionFailureBucket {
    // 사용자가 상품 아닌 걸 넣었다. 정상 트래픽이라 할 일이 없다.
    NOT_PRODUCT,

    // 우리 구성으로 그 페이지를 못 읽었다(빈 셸·추출할 본문 없음). 늘면 도메인 허가 후보를 본다.
    UNREADABLE,

    // 대상이 우리를 막았다. 늘면 UNSUPPORTED 정책 후보를 본다.
    BLOCKED,

    // 추출은 됐는데 값을 믿을 수 없다. 늘면 모델·프롬프트·검증 규칙을 본다.
    EXTRACT_QUALITY,

    // 우리 버그이거나 우리 방어가 발동했다. 늘면 코드를 조사한다.
    INTERNAL_ERROR,
}

// 확정 실패 예외가 자기 bucket 을 스스로 밝히게 하는 ErrorCode. 예외 클래스가 늘어도 워커는 이 인터페이스만
// 보므로 reason 파생 경로가 한 줄로 유지되고, bucket 은 code 정의 옆(single source)에 박힌다.
//
// bucket 이 nullable 인 이유: 일시(transient) code 는 bucket 이 없다(카탈로그도 같은 모양). 일시 실패는
// 소유권 반납으로 되살아나 종결 집계(reason)에 닿지 않고, 상한을 소진하면 recover 가 retry_exhausted 로 센다.
interface ExtractionFailureCode : ErrorCode {
    val bucket: ExtractionFailureBucket?
}
