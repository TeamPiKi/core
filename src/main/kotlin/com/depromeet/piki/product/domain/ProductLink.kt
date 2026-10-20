package com.depromeet.piki.product.domain

import java.net.URI

class ProductLink private constructor(
    val value: URI,
) {
    override fun toString(): String = value.toString()

    // 부분 문자열이 아니라 도메인 단위로 본다. evil-naver.com 이 naver.com 으로 통과하면 안 된다
    // domains 는 소문자이고 끝에 점이 없어야 한다. 그 정규화는 목록을 만드는 쪽이 한다
    fun matchesAnyDomain(domains: Collection<String>): Boolean {
        val host = normalizedHost()
        return domains.any { host == it || host.endsWith(".$it") }
    }

    // "naver.com." 같은 끝점 표기로 도메인 판정을 우회하지 못하게 끝점을 뗀다
    fun normalizedHost(): String = value.host.trimEnd('.').lowercase()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProductLink) return false
        return value == other.value
    }

    override fun hashCode(): Int = value.hashCode()

    companion object {
        // 모든 URL 길이 상한
        const val MAX_LENGTH = 768

        private val ENTRY_SCHEMES = setOf("https")

        // 귀결점은 extractor 가 홉마다 http 도 따라가 http 일 수 있다. 정체 URL 은 https 로 다시 조립해 하나로 모인다(NormalizedInput)
        private val HOP_SCHEMES = setOf("http", "https")

        fun parse(raw: String): ProductLink = parse(raw, ENTRY_SCHEMES)

        // 상품 주소 규칙에 안 맞는 귀결점은 없는 것으로 본다. 추출기가 돌려준 귀결점처럼 우리가 검증하지 않은 출처에서 쓴다
        fun parseHopOrNull(raw: String): ProductLink? =
            try {
                parse(raw, HOP_SCHEMES)
            } catch (e: ProductLinkException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }

        private fun parse(
            raw: String,
            schemes: Set<String>,
        ): ProductLink {
            val trimmed = raw.trim()
            // 빈 값은 요청 DTO 가 먼저 걸러 여기 닿지 않는다. 닿으면 캐치올이 이 문구를 응답 detail 로 내보내므로 사용자 문구로 둔다
            require(trimmed.isNotBlank()) { "링크를 입력해 주세요." }
            val uri =
                try {
                    URI.create(trimmed)
                } catch (e: IllegalArgumentException) {
                    throw ProductLinkException.invalidFormat(e)
                }
            // URI.create 는 스킴 없는 "example.com/product" 도 통과시킨다
            if (uri.scheme?.lowercase() !in schemes) throw ProductLinkException.unsupportedScheme()
            // "https:foo" 나 호스트에 밑줄이 든 주소는 URI 로는 읽히지만 host 가 없다
            uri.host ?: throw ProductLinkException.invalidFormat()
            return ProductLink(uri)
        }
    }
}
