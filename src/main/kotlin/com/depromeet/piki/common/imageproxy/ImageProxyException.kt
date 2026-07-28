package com.depromeet.piki.common.imageproxy

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// 외부 이미지 프록시(SSRF 방어 · 크기 상한 · fetch) 실패의 계약 예외.
// message·category·httpStatus 는 전부 errorCode 하나에서 파생한다(ImageProxyErrorCode 가 single source).
class ImageProxyException private constructor(
    override val errorCode: ErrorCode,
) : BaseException(errorCode.message),
    HttpMappable {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        fun blockedDomain(): ImageProxyException = ImageProxyException(ImageProxyErrorCode.BLOCKED_DOMAIN)

        fun imageTooLarge(): ImageProxyException = ImageProxyException(ImageProxyErrorCode.IMAGE_TOO_LARGE)

        fun fetchFailed(): ImageProxyException = ImageProxyException(ImageProxyErrorCode.FETCH_FAILED)
    }
}
