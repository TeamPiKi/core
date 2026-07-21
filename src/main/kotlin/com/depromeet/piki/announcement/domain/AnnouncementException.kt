package com.depromeet.piki.announcement.domain

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// 공지 도메인 예외. message·category·httpStatus 는 전부 errorCode 하나에서 파생한다(AnnouncementErrorCode 가 single source).
// message 는 사용자 대면 고정 문구 — 내부 정보(미발송 공지 존재 등)를 노출하지 않는다.
class AnnouncementException private constructor(
    override val errorCode: ErrorCode,
) : BaseException(errorCode.message),
    HttpMappable {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        // 발송 완료(SENT)되지 않았거나 존재하지 않는 공지. 미발송(DRAFT/SCHEDULED 등) 공지의 존재를 노출하지
        // 않기 위해 "없음"과 동일하게 404 로 응답한다.
        fun notFound(): AnnouncementException = AnnouncementException(AnnouncementErrorCode.NOT_FOUND)

        // 커서가 숫자로 변환되지 않는 등 잘못된 페이지 요청.
        fun invalidCursor(): AnnouncementException = AnnouncementException(AnnouncementErrorCode.INVALID_CURSOR)
    }
}
