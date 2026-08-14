package com.depromeet.piki.product.service

import com.depromeet.piki.common.exception.ErrorCategory

// ProductSnapshotException 의 code 배정표(에픽 #728). 번호는 append-only — 재배치·결번 침범 금지.
//
// ⚠️ 이 enum 은 ErrorCodeRegistry.all 에 **의도적으로 등록하지 않는다**(AnnouncementImageErrorCode 와 같은 선례).
// 유일한 생성 경로인 ProductSnapshot.fromExtracted · RemoteExtractionContract.translate 는 비동기 파싱
// 워커(AsyncItemParsingWorker · AsyncImageParsingWorker)에서만 호출된다 — 워커가 예외를 잡아 item 을 FAILED 로
// 전이시키고 메트릭 reason(아래 bucket 에서 파생)으로 집계할 뿐, GlobalExceptionHandler 를 거치지 않아 응답 code 로 나가지 않는다.
// 클라가 절대 받을 수 없는 code 를 공개 카탈로그에 넣으면 code→문구 매핑에 노이즈만 된다.
// 여기서 code 를 부여하는 목적은 오직 예외 클래스 모양을 다른 도메인 예외와 통일(errorCode 참조)하는 것뿐이다.
//
// bucket 은 그 실패를 메트릭에서 무엇으로 셀지의 정본이다(#936) — 파싱 메트릭 reason 이 여기서 파생하므로,
// code 를 더할 때 bucket 도 함께 정한다. 원격 code → 여기의 어느 엔트리인지는 RemoteExtractionContract 가 정한다.
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
