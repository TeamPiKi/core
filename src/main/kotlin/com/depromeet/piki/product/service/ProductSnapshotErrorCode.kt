package com.depromeet.piki.product.service

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode

// ProductSnapshotException 의 code 배정표(에픽 #728). 번호는 append-only — 재배치·결번 침범 금지.
//
// ⚠️ 이 enum 은 ErrorCodeRegistry.all 에 **의도적으로 등록하지 않는다**(AnnouncementImageErrorCode 와 같은 선례).
// 유일한 생성 경로인 ProductSnapshot.fromExtracted · RemoteExtractionContract.translate 는 비동기 파싱
// 워커(AsyncItemParsingWorker · AsyncImageParsingWorker)에서만 호출된다 — 워커가 예외를 잡아 item 을 FAILED 로
// 전이시키고 메트릭 reason=not_product 로 집계할 뿐, GlobalExceptionHandler 를 거치지 않아 응답 code 로 나가지 않는다.
// 클라가 절대 받을 수 없는 code 를 공개 카탈로그에 넣으면 code→문구 매핑에 노이즈만 된다.
// 여기서 code 를 부여하는 목적은 오직 예외 클래스 모양을 다른 도메인 예외와 통일(errorCode 참조)하는 것뿐이다.
enum class ProductSnapshotErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
) : ErrorCode {
    // LLM 이 "상품 페이지가 아님"으로 판정. 링크 재등록·재시도 모두 무의미.
    NOT_PRODUCT_PAGE("SNAPSHOT-001", ErrorCategory.INVALID_INPUT, "상품 페이지 링크만 등록할 수 있어요."),

    // 추출값이 유효 범위(가격 음수, 컬럼 길이 초과 등)를 벗어남. 추출 결과를 신뢰할 수 없다.
    // 구체 사유(어느 필드가 왜)는 message 에 담지 않고 로그로 남긴다.
    UNTRUSTWORTHY_VALUE("SNAPSHOT-002", ErrorCategory.INVALID_INPUT, "상품 정보를 확인하지 못했어요. 직접 입력해 주세요."),
}
