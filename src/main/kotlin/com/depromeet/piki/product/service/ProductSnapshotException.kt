package com.depromeet.piki.product.service

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// 추출 결과 검증 실패. message·category·httpStatus 는 전부 errorCode 하나에서 파생한다
// (ProductSnapshotErrorCode 가 single source).
// errorCode 는 클래스 모양 통일 목적이며, 비동기 워커 전용이라 공개 카탈로그에 등록하지 않는다
// (ProductSnapshotErrorCode 주석 참고).
class ProductSnapshotException private constructor(
    override val errorCode: ErrorCode,
) : BaseException(errorCode.message),
    HttpMappable {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        // LLM 이 "상품 페이지가 아님"으로 판정. 링크 재등록·재시도 모두 무의미.
        fun notProductPage(): ProductSnapshotException = ProductSnapshotException(ProductSnapshotErrorCode.NOT_PRODUCT_PAGE)

        // 추출값이 유효 범위(가격 음수, 컬럼 길이 초과 등)를 벗어남. 추출 결과를 신뢰할 수 없다.
        fun untrustworthyValue(): ProductSnapshotException = ProductSnapshotException(ProductSnapshotErrorCode.UNTRUSTWORTHY_VALUE)
    }
}
