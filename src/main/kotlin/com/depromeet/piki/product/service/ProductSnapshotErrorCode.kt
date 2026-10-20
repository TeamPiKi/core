package com.depromeet.piki.product.service

import com.depromeet.piki.common.exception.ErrorCategory

// ProductSnapshotException 의 code 배정표(#728). 번호는 append-only
// 소비처가 파서뿐이라 응답 code 로 나가지 않아 ErrorCodeRegistry 에 등록하지 않음
// bucket 이 파싱 메트릭 reason 의 정본(#936). code 를 더하면 bucket 도 함께 정함
enum class ProductSnapshotErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
    override val bucket: ExtractionFailureBucket,
) : ExtractionFailureCode {
    // 형식 위반·없는 host 라 상품 링크가 될 수 없음. 링크 재등록·재시도 모두 무의미.
    NOT_PRODUCT_PAGE(
        "SNAPSHOT-001",
        ErrorCategory.INVALID_INPUT,
        "상품 페이지 링크만 등록할 수 있어요.",
        ExtractionFailureBucket.NOT_PRODUCT,
    ),

    // 추출값이 유효 범위(가격 음수, 컬럼 길이 초과 등)를 벗어남. 추출 결과를 신뢰할 수 없다.
    // 구체 사유(어느 필드가 왜)는 message 에 담지 않고 로그로 남긴다.
    // bucket 이 not_product 가 아닌 이유(#936): "상품 아님"과 성격이 다르고 — 추출 자체는 됐는데 값을 못 믿는 것 —
    // 대응도 모델·프롬프트·검증 규칙 쪽이라, 한 통에 두면 "상품 아님" 지표가 두 배로 부풀어 판단을 흐린다.
    UNTRUSTWORTHY_VALUE(
        "SNAPSHOT-002",
        ErrorCategory.INVALID_INPUT,
        "상품 정보를 확인하지 못했어요. 직접 입력해 주세요.",
        ExtractionFailureBucket.EXTRACT_QUALITY,
    ),

    // SNAPSHOT-003 은 결번 — 받은 페이지 기반 판정(읽을 내용 없음)이 일시 실패로 바뀌며 소멸(infra#145).
}
