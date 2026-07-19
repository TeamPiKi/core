package com.depromeet.piki.product.domain

import java.net.URI

class ProductLink private constructor(
    val value: URI,
) {
    override fun toString(): String = value.toString()

    // 로그/메트릭용. 쿼리스트링·fragment 에 토큰/세션이 섞일 수 있어 host+path 만 노출한다.
    fun safeLogString(): String = "${value.host ?: "?"}${value.rawPath ?: ""}"

    // host 가 주어진 도메인 목록의 항목과 같거나 그 서브도메인이면 true 인 도메인 단위 매칭의 단일 술어.
    // 플랫폼 라우팅 정책 판정(ExtractionRoutingPolicy)이 쓰는 도메인 매칭 술어다 —
    // 정규화 규칙(trailing dot 제거, lowercase, 부분 문자열이 아닌 도메인 단위)이 바뀔 때 사본들이 갈라지지 않게
    // 도메인이 규칙의 주인을 맡는다. host 가 없으면(형식 이상은 parse 가 이미 처리) 어느 목록과도 매칭되지 않는다.
    // trailing dot(절대 도메인 표기, 예: "naver.com.")은 제거해 우회를 막는다. Kotlin lowercase() 는 locale 무관(invariant).
    // domains 항목은 소문자·trailing dot 없는 정규형이어야 한다 — 출처(DB 정책의 admin 경계와 엔티티 불변식)가
    // 정규화를 책임진다. 새 출처가 생기면 그 출처가 같은 정규화를 진다.
    fun matchesAnyDomain(domains: Collection<String>): Boolean {
        val host = normalizedHost() ?: return false
        return domains.any { host == it || host.endsWith(".$it") }
    }

    // host 정규형(trailing dot 제거·lowercase). 도메인 매칭(위 matchesAnyDomain)과 출처 몰 표시명 유도
    // (SourcePlatformResolver)가 같은 정규형을 공유한다 — 규칙이 갈라지지 않게 여기가 주인이다.
    fun normalizedHost(): String? = value.host?.trimEnd('.')?.lowercase()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProductLink) return false
        return value == other.value
    }

    override fun hashCode(): Int = value.hashCode()

    companion object {
        private val HTTP_SCHEMES = setOf("https")

        fun parse(raw: String): ProductLink {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) throw ProductLinkException.blank()
            val uri =
                try {
                    URI.create(trimmed)
                } catch (e: IllegalArgumentException) {
                    throw ProductLinkException.invalidFormat(e)
                }
            // URI.create 는 스킴 없는 "example.com/product" 도 relative URI 로 통과시키므로 명시 검증.
            // RFC 3986 은 scheme 을 case-insensitive 로 정의하므로 비교 전에 lowercase 정규화한다.
            if (uri.scheme?.lowercase() !in HTTP_SCHEMES) throw ProductLinkException.unsupportedScheme()
            return ProductLink(uri)
        }
    }
}
