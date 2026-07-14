package com.depromeet.piki.user.domain

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode

// UserException 의 code 배정표(에픽 #728 부록 A). 번호는 append-only — 재배치·결번 침범 금지.
// code·category·message 를 한 엔트리에 모아 single source 로 둔다: status 는 category.httpStatus 로,
// 응답 detail·로그·OpenAPI 문서·코드 카탈로그는 message 로 파생된다.
// 구 invalidNickname(reason) 동적 message 는 유한한 3케이스(blank/tooLong/reserved)라 3코드로 정적화한다.
// USER-006 은 blank 가 승계하고, 나머지 둘은 뒤에 append(USER-012·013).
enum class UserErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
) : ErrorCode {
    NOT_FOUND("USER-001", ErrorCategory.NOT_FOUND, "존재하지 않는 계정이에요."),
    ALREADY_MEMBER("USER-002", ErrorCategory.CONFLICT, "이미 가입된 계정이에요."),
    DELETED_USER("USER-003", ErrorCategory.CONFLICT, "탈퇴한 계정이에요."),
    DUPLICATE_NICKNAME("USER-004", ErrorCategory.CONFLICT, "이미 누군가 쓰고 있는 닉네임이에요. 다른 걸 입력해 주세요."),
    NICKNAME_GENERATION_FAILED("USER-005", ErrorCategory.SERVER_ERROR, "닉네임을 만들지 못했어요. 잠시 후 다시 시도해 주세요."),
    NICKNAME_BLANK("USER-006", ErrorCategory.INVALID_INPUT, "닉네임을 입력해 주세요."),
    GUEST_CANNOT_WITHDRAW("USER-007", ErrorCategory.FORBIDDEN, "게스트는 탈퇴할 수 없어요."),
    GUEST_CANNOT_UPDATE_PROFILE_IMAGE("USER-008", ErrorCategory.FORBIDDEN, "프로필 이미지는 회원만 바꿀 수 있어요."),
    EMPTY_PROFILE_IMAGE("USER-009", ErrorCategory.INVALID_INPUT, "빈 이미지 파일은 올릴 수 없어요."),
    UNSUPPORTED_PROFILE_IMAGE_TYPE("USER-010", ErrorCategory.INVALID_INPUT, "지원하지 않는 이미지 형식이에요."),
    MALFORMED_PROFILE_IMAGE("USER-011", ErrorCategory.INVALID_INPUT, "이미지 파일이 손상되었거나 형식이 맞지 않아요."),
    NICKNAME_TOO_LONG("USER-012", ErrorCategory.INVALID_INPUT, "닉네임은 ${User.NICKNAME_MAX_LENGTH}자까지 입력할 수 있어요."),
    NICKNAME_RESERVED("USER-013", ErrorCategory.INVALID_INPUT, "사용할 수 없는 닉네임이에요."),
}
