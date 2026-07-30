package com.depromeet.piki.product.service

import com.depromeet.piki.product.domain.CurrencyCode
import com.depromeet.piki.product.domain.ProductLink

// 상품 추출 시점의 상태를 캡처한 결과. URL 추출(link)·이미지 추출(image) 두 경로가 공유하는 표현이며,
// 영속 표현(Item 엔티티)과 분리되어 extract 가 트랜잭션·영속 컨텍스트 바깥에서 다뤄질 수 있게 한다.
// 이미지 추출은 URL 이 없어 link 가 null 이다.
data class ProductSnapshot(
    val link: ProductLink? = null,
    val name: String? = null,
    val imageUrl: String? = null,
    val currentPrice: Int? = null,
    val currency: String? = null,
    // 리다이렉트를 따라간 최종 페이지 URL(extractor 계약의 additive 필드). 상품 정체성(canonical, #825) 정규화의
    // 입력이며, 구버전 extractor·이미지 경로에선 null 이다 — null 이면 정체성 확정을 건너뛴다(배포 순서 무관).
    val finalUrl: String? = null,
    // 값을 만든 추출 경로의 wire 문자열(STRUCTURED|LLM). 소비처(ItemSnapshotSource.fromWireMethod)가 출처 enum 으로
    // 번역하고, 모르는 값·null 은 출처 미기록으로 둔다(tolerant). 정규화·검증 대상이 아니라 원문 그대로 나른다.
    val extractionMethod: String? = null,
) {
    companion object {
        // items 테이블 컬럼 길이와 일치시킨다.
        private const val NAME_MAX_LENGTH = 512
        private const val IMAGE_URL_MAX_LENGTH = 2048

        // 원시 추출값(구조화 파싱·LLM 추출이 공유)을 정규화·범위검증해 만드는 단일 진실 원천.
        // name blank→null, imageUrl 은 https 만(클라이언트가 <img src> 로 쓸 때의 XSS 사다리 차단),
        // currency 는 ISO 4217 로 정규화한다. 추출값이 DB 컬럼 제약·상식을 벗어나면(가격 음수·길이 초과)
        // 추출 실패로 보고 untrustworthyValue 를 던진다 — 입력 경계의 계약 검증.
        //
        // 실패 처리는 호출부가 고른다: 구조화 경로는 이 예외를 runCatching 으로 흡수해 Miss.INVALID_VALUE(→LLM fallback)로,
        // LLM 경로는 그대로 흘려 FAILED 로 떨어뜨린다. 같은 검증, 실패 표현만 다르다.
        fun fromExtracted(
            link: ProductLink?,
            name: String?,
            imageUrl: String?,
            currentPrice: Int?,
            currency: String?,
            // 출처 메타(finalUrl·extractionMethod)는 값 검증 대상이 아니라 그대로 나른다 — finalUrl 의 정규화·길이
            // 판정은 정체성 확정 지점(CanonicalLink)이 지고, method 는 소비처가 tolerant 하게 번역한다.
            finalUrl: String? = null,
            extractionMethod: String? = null,
        ): ProductSnapshot {
            val normalizedName = name?.takeIf { it.isNotBlank() }
            val normalizedImageUrl =
                imageUrl?.takeIf { it.isNotBlank() && it.startsWith("https://", ignoreCase = true) }
            val normalizedCurrency = CurrencyCode.normalizeOrNull(currency)

            if ((currentPrice ?: 0) < 0) {
                throw ProductSnapshotException.untrustworthyValue()
            }
            if ((normalizedName?.length ?: 0) > NAME_MAX_LENGTH) {
                throw ProductSnapshotException.untrustworthyValue()
            }
            if ((normalizedImageUrl?.length ?: 0) > IMAGE_URL_MAX_LENGTH) {
                throw ProductSnapshotException.untrustworthyValue()
            }

            return ProductSnapshot(
                link = link,
                name = normalizedName,
                imageUrl = normalizedImageUrl,
                currentPrice = currentPrice,
                currency = normalizedCurrency,
                finalUrl = finalUrl,
                extractionMethod = extractionMethod,
            )
        }
    }
}
