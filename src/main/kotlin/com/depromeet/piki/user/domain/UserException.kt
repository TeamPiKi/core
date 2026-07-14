package com.depromeet.piki.user.domain

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// userId 등 내부 식별자는 응답 detail 로 노출하지 않는다(GlobalExceptionHandler 가 message 를 detail 로 내보냄).
// 디버깅에 필요한 userId 는 인증 컨텍스트·trace 로 추적 가능하므로 message 에는 고정 사용자 문구만 둔다.
// message·category·httpStatus 는 전부 errorCode 하나에서 파생한다(UserErrorCode 가 single source).
class UserException private constructor(
    override val errorCode: ErrorCode,
) : BaseException(errorCode.message),
    HttpMappable {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        fun notFound(): UserException = UserException(UserErrorCode.NOT_FOUND)

        fun alreadyMember(): UserException = UserException(UserErrorCode.ALREADY_MEMBER)

        fun deletedUser(): UserException = UserException(UserErrorCode.DELETED_USER)

        fun duplicateNickname(): UserException = UserException(UserErrorCode.DUPLICATE_NICKNAME)

        fun nicknameGenerationFailed(): UserException = UserException(UserErrorCode.NICKNAME_GENERATION_FAILED)

        // 구 invalidNickname(reason) 을 3케이스로 분리(에픽 #728). reason 은 User.validateNickname 이 넘기던
        // 3개 고정 문구뿐이었으므로, 코드↔문구 1:1 로 정적화한다.
        fun nicknameBlank(): UserException = UserException(UserErrorCode.NICKNAME_BLANK)

        fun nicknameTooLong(): UserException = UserException(UserErrorCode.NICKNAME_TOO_LONG)

        fun nicknameReserved(): UserException = UserException(UserErrorCode.NICKNAME_RESERVED)

        // 게스트 탈퇴 거부 — 멀쩡한 클라이언트(게스트 토큰)가 정상 요청으로 닿을 수 있는 계약 응답이라 커스텀 예외(403).
        // 게스트는 보존할 PII 도, 스토어 요건상 "계정"도 없고, 공유 토너먼트 참조 때문에 하드삭제도 불가하다.
        fun guestCannotWithdraw(): UserException = UserException(UserErrorCode.GUEST_CANNOT_WITHDRAW)

        // 프로필 이미지 수정은 MEMBER 전용 — 게스트가 이미지 파트를 담아 PATCH /me 를 호출하면 닿는 계약 응답(403).
        fun guestCannotUpdateProfileImage(): UserException = UserException(UserErrorCode.GUEST_CANNOT_UPDATE_PROFILE_IMAGE)

        fun emptyProfileImage(): UserException = UserException(UserErrorCode.EMPTY_PROFILE_IMAGE)

        fun unsupportedProfileImageType(): UserException = UserException(UserErrorCode.UNSUPPORTED_PROFILE_IMAGE_TYPE)

        // 선언한 Content-Type 과 실제 파일 바이트(매직바이트)가 어긋나거나 이미지로 해석되지 않을 때.
        // Content-Type 헤더는 클라이언트가 위조할 수 있으므로 실제 시그니처로 교차검증한다.
        fun malformedProfileImage(): UserException = UserException(UserErrorCode.MALFORMED_PROFILE_IMAGE)
    }
}
