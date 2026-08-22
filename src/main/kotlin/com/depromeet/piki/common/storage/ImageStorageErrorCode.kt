package com.depromeet.piki.common.storage

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode

// ImageStorageException 의 code 배정표(에픽 #728). 번호는 append-only — 재배치·결번 침범 금지.
// code·category·message 를 한 엔트리에 모아 single source 로 둔다: status 는 category.httpStatus 로,
// 응답 detail·로그·OpenAPI 카탈로그는 message 로 파생된다.
//
// 전부 RETRYABLE(502) 이다 — 외부 스토리지(S3)의 일시 장애라 클라이언트가 재시도로 대응할 수 있다.
// 같은 502 라도 SERVER_ERROR(재시도 무의미)로 두면 클라가 재시도를 포기하므로 구분을 유지한다.
// 실패한 연산별로 code 를 나눈 이유: status·category 가 같아도 사용자에게 안내할 상황이 다르다
// (저장 실패는 다시 올리기, 발급 실패는 업로드를 시작하기도 전 단계, 존재 확인 실패는 이미 올린 뒤의 확정 단계).
//
// 앞의 셋만 ErrorCodeRegistry 에 등록한다 — DELETE_FAILED 는 응답에 실릴 수 없어서다(아래 주석).
// 등록분이 카탈로그에서 연속 번호로 읽히도록 미등록분을 뒤 번호에 둔다.
enum class ImageStorageErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
) : ErrorCode {
    UPLOAD_FAILED("STORAGE-001", ErrorCategory.RETRYABLE, "이미지를 저장하지 못했어요. 잠시 후 다시 시도해 주세요."),
    PRESIGN_FAILED("STORAGE-002", ErrorCategory.RETRYABLE, "이미지 업로드 URL 을 발급하지 못했어요. 잠시 후 다시 시도해 주세요."),
    EXISTS_CHECK_FAILED("STORAGE-003", ErrorCategory.RETRYABLE, "이미지 업로드 상태를 확인하지 못했어요. 잠시 후 다시 시도해 주세요."),

    // ⚠️ 이 하나만 ErrorCodeRegistry 에 등록하지 않는다. 삭제 실패는 호출부가 전부 runCatching 으로
    // 삼키고 warn 로그만 남긴다 — 탈퇴 시 프로필 파기(WithdrawalService)·공지 이미지 정리(AdminAnnouncementService)는
    // 본 작업을 성공 처리하고 후속 정리 대상으로 넘긴다.
    // 따라서 GlobalExceptionHandler 에 닿지 않아 wire code 로 나갈 수 없고, 클라가 절대 못 받는 code 를
    // 공개 카탈로그에 두면 code→문구 매핑에 노이즈만 된다(SNAPSHOT·EXTRACTOR 미등록과 같은 기준).
    // 그럼에도 엔트리를 두는 이유는 S3ImageStorage 가 실제로 이 예외를 던지기 때문이다 — 예외 클래스 모양을
    // 다른 도메인과 통일(errorCode 참조)하려면 참조할 code 가 하나 있어야 한다.
    // 호출부가 runCatching 을 걷어내 응답으로 내보내게 되면 그때 registry 에 등록하면 된다(번호는 그대로).
    DELETE_FAILED("STORAGE-004", ErrorCategory.RETRYABLE, "이미지를 삭제하지 못했어요. 잠시 후 다시 시도해 주세요."),
}
