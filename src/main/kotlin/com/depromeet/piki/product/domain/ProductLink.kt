package com.depromeet.piki.product.domain

import java.net.URI

class ProductLink private constructor(
    val value: URI,
) {
    override fun toString(): String = value.toString()

    // 쿼리와 fragment 에는 토큰이 실릴 수 있어 로그에는 host 와 path 만 남긴다
    fun safeLogString(): String = "${value.host}${value.rawPath.orEmpty()}"

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
        // 받는 링크와 저장하는 링크의 길이 상한. 요청 검증과 링크를 담는 칸이 모두 이 값을 쓴다
        const val MAX_LENGTH = 2048

        private val HTTP_SCHEMES = setOf("https")

        fun parse(raw: String): ProductLink {
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
            if (uri.scheme?.lowercase() !in HTTP_SCHEMES) throw ProductLinkException.unsupportedScheme()
            // "https:foo" 나 호스트에 밑줄이 든 주소는 URI 로는 읽히지만 host 가 없다
            uri.host ?: throw ProductLinkException.invalidFormat()
            return ProductLink(uri)
        }
    }
}
