package com.depromeet.piki.image.domain

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// 업로드된 이미지 검증 실패를 나타내는 계약 예외. 빈 파일·미지정/미지원 형식은 모두 사용자가 올린 이미지로
// 정상 요청으로 도달 가능하므로(계약), require(불변식·500)가 아니라 커스텀 예외로 400 을 명시한다.
// message·category·httpStatus 가 errorCode 한 곳에서 파생돼, 호출 위치와 무관하게 같은 응답이 나온다
// (ProductImageErrorCode 가 single source).
class ProductImageException private constructor(
    override val errorCode: ErrorCode,
) : BaseException(errorCode.message),
    HttpMappable {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        fun emptyImage(): ProductImageException = ProductImageException(ProductImageErrorCode.EMPTY_IMAGE)

        fun unknownType(): ProductImageException = ProductImageException(ProductImageErrorCode.UNKNOWN_TYPE)

        fun unsupportedType(): ProductImageException = ProductImageException(ProductImageErrorCode.UNSUPPORTED_TYPE)
    }
}
