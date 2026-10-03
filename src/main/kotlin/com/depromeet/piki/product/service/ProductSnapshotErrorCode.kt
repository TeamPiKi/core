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
    // LLM 이 "상품 페이지가 아님"으로 판정. 링크 재등록·재시도 모두 무의미.
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

    // 우리가 그 페이지에서 읽어낼 본문을 얻지 못함(데이터 없는 CSR 셸·가시 텍스트 부재). 상품이 아닌 게 아니라
    // **지금 우리 구성으로 못 읽는** 것이라 도메인 허가 후보를 찾는 신호다.
    // message 는 UNTRUSTWORTHY_VALUE 와 같다 — 사용자가 취할 행동(직접 입력)이 같고, 구분은 detail 이 아니라
    // bucket·로그가 진다(CLAUDE.md 메시지 톤).
    NO_EXTRACTABLE_CONTENT(
        "SNAPSHOT-003",
        ErrorCategory.INVALID_INPUT,
        "상품 정보를 확인하지 못했어요. 직접 입력해 주세요.",
        ExtractionFailureBucket.UNREADABLE,
    ),
}
