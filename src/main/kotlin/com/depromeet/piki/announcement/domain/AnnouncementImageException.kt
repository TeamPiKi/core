package com.depromeet.piki.announcement.domain

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// 공지 본문 이미지 rehost(외부 URL → 우리 S3, #561) 실패 예외.
// 운영자가 붙여넣은 이미지 주소가 원인이라(멀쩡한 호출이 정상 작성으로 도달 가능) 커스텀 예외로 둔다.
// message·category·httpStatus 는 전부 errorCode 하나에서 파생한다(AnnouncementImageErrorCode 가 single source).
// message 는 운영자 대면 고정 문구 — SSRF 차단 사유 등 내부 정보를 노출하지 않는다(구체 사유는 로그로).
// errorCode 는 클래스 모양 통일 목적이며, 어드민 SSR 전용이라 공개 카탈로그에 등록하지 않는다(AnnouncementImageErrorCode 주석 참고).
class AnnouncementImageException private constructor(
    override val errorCode: ErrorCode,
) : BaseException(errorCode.message),
    HttpMappable {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        fun unsupportedType(): AnnouncementImageException = AnnouncementImageException(AnnouncementImageErrorCode.UNSUPPORTED_TYPE)

        fun malformed(): AnnouncementImageException = AnnouncementImageException(AnnouncementImageErrorCode.MALFORMED)

        fun tooLarge(): AnnouncementImageException = AnnouncementImageException(AnnouncementImageErrorCode.TOO_LARGE)

        fun fetchFailed(): AnnouncementImageException = AnnouncementImageException(AnnouncementImageErrorCode.FETCH_FAILED)

        fun blockedUrl(): AnnouncementImageException = AnnouncementImageException(AnnouncementImageErrorCode.BLOCKED_URL)
    }
}
