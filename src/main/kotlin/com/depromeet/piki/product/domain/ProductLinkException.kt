package com.depromeet.piki.product.domain

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// 링크 등록 입력 경계의 계약 예외. message·category·httpStatus 는 전부 errorCode 하나에서 파생한다
// (ProductLinkErrorCode 가 single source).
class ProductLinkException private constructor(
    override val errorCode: ErrorCode,
    cause: Throwable? = null,
) : BaseException(errorCode.message, cause),
    HttpMappable {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        // 원본 URL 은 message 에 박지 않는다. GlobalExceptionHandler 가 message 를 응답 detail·로그
        // 양쪽에 박는 구조라, 쿼리스트링/fragment 에 섞일 수 있는 토큰·세션이 외부로 새는 경로가 되기 때문.
        // 디버깅용 컨텍스트는 cause 로 연결해 stack trace 로만 남긴다.
        fun invalidFormat(cause: Throwable): ProductLinkException = ProductLinkException(ProductLinkErrorCode.INVALID_FORMAT, cause)

        fun unsupportedScheme(): ProductLinkException = ProductLinkException(ProductLinkErrorCode.UNSUPPORTED_SCHEME)

        // fetch 로 상품 정보를 가져올 수 없는 플랫폼(직접 접근을 봇 차단하는 쇼핑몰)을 등록 시점에 거른다.
        // 어느 플랫폼인지는 message 에 박지 않는다(safeLogString 으로 로그). 사용자에겐 "아직 안 되는 곳" 안내만.
        fun unsupportedPlatform(): ProductLinkException = ProductLinkException(ProductLinkErrorCode.UNSUPPORTED_PLATFORM)
    }
}
