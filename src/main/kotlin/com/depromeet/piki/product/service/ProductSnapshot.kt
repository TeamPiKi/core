package com.depromeet.piki.product.service

import com.depromeet.piki.contracts.extraction.v1.ExtractionMethod
import com.depromeet.piki.product.domain.CurrencyCode
import com.depromeet.piki.product.domain.ProductLink

// 추출 결과. 링크 추출과 이미지 추출이 같은 모양으로 돌려준다. 엔티티가 아니라서 추출을 트랜잭션 밖에서 끝낼 수 있다
data class ProductSnapshot(
    val name: String? = null,
    val imageUrl: String? = null,
    val price: Int? = null,
    val currency: String? = null,
    // 리다이렉트를 따라간 최종 페이지 URL. 아이템 정체 URL 의 재료이고 이미지 추출에는 없다
    val finalUrl: ProductLink? = null,
    val method: ExtractionMethod? = null,
) {
    companion object {
        // extractor 가 정상 값을 보장해도 신뢰 경계를 넘어온 값이라 다시 거른다
        // 이미지 URL 은 https 만 받는다. 클라이언트가 <img src> 로 쓰므로 다른 스킴은 XSS 통로가 된다
        fun fromExtracted(
            name: String?,
            imageUrl: String?,
            price: Int?,
            currency: String?,
            finalUrl: String? = null,
            method: ExtractionMethod? = null,
        ): ProductSnapshot {
            if ((price ?: 0) < 0) throw ProductSnapshotException.untrustworthyValue()
            return ProductSnapshot(
                name = name?.takeIf { it.isNotBlank() },
                imageUrl = imageUrl?.takeIf { it.isNotBlank() && it.startsWith("https://", ignoreCase = true) },
                price = price,
                currency = CurrencyCode.normalizeOrNull(currency),
                // 귀결점이 상품 주소 규칙에 안 맞으면 등록 입력이 정체가 된다
                finalUrl = finalUrl?.let(ProductLink::parseOrNull),
                // 계약 밖의 값은 기록하지 않는다
                method = method?.takeUnless { it == ExtractionMethod.EXTRACTION_METHOD_UNSPECIFIED || it == ExtractionMethod.UNRECOGNIZED },
            )
        }
    }
}
