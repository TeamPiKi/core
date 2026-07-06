package com.depromeet.piki.product.domain

import java.net.URI

class ProductLink private constructor(
    val value: URI,
) {
    override fun toString(): String = value.toString()

    // 로그/메트릭용. 쿼리스트링·fragment 에 토큰/세션이 섞일 수 있어 host+path 만 노출한다.
    fun safeLogString(): String = "${value.host ?: "?"}${value.rawPath ?: ""}"

    // 등록 입력 경계 전용 — fetch 로 상품 정보를 가져올 수 없는 플랫폼이면 등록을 거른다(400).
    // parse(형식 불변식)와 분리해 여기서만 검사한다: parse 는 DB 읽기(ProductLinkConverter)·redirect 추적과
    // 공유되므로, 미지원 판정을 parse 에 넣으면 이미 저장된 미지원 URL 을 조회할 때 깨지고, 차단이 시점에 따라
    // 풀려도(봇 차단은 변동적) 과거 행 읽기가 막힌다. 미지원은 "정책 계약"이라 입력 경계가 진다.
    fun verifySupportedPlatform() {
        if (isFromUnsupportedPlatform()) throw ProductLinkException.unsupportedPlatform()
    }

    // host 가 미지원 목록과 같거나 그 서브도메인이면 미지원. 판정은 matchesAnyDomain(단일 술어)이 진다.
    private fun isFromUnsupportedPlatform(): Boolean = matchesAnyDomain(UNSUPPORTED_HOSTS)

    // host 가 주어진 도메인 목록의 항목과 같거나 그 서브도메인이면 true 인 도메인 단위 매칭의 단일 술어.
    // 미지원 플랫폼 판정과 원격 추출 라우팅(RoutingProductLinkExtractor)이 공유한다 — 정규화 규칙(trailing dot 제거,
    // lowercase, 부분 문자열이 아닌 도메인 단위)이 바뀔 때 사본들이 갈라지지 않게 도메인이 규칙의 주인을 맡는다.
    // host 가 없으면(형식 이상은 parse 가 이미 처리) 어느 목록과도 매칭되지 않는다.
    // trailing dot(절대 도메인 표기, 예: "naver.com.")은 제거해 우회를 막는다. Kotlin lowercase() 는 locale 무관(invariant).
    // domains 항목은 소문자·trailing dot 없는 정규형이어야 한다 (UNSUPPORTED_HOSTS 는 하드코딩 정규형,
    // 외부 설정 출처는 호출자가 정규화해 넘긴다 — RemoteExtractionProperties.normalizedHosts).
    fun matchesAnyDomain(domains: Collection<String>): Boolean {
        val host = value.host?.trimEnd('.')?.lowercase() ?: return false
        return domains.any { host == it || host.endsWith(".$it") }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProductLink) return false
        return value == other.value
    }

    override fun hashCode(): Int = value.hashCode()

    companion object {
        private val HTTP_SCHEMES = setOf("https")

        // 직접 GET 접근을 봇 차단(TLS 지문·WAF·JS 챌린지)해 fetch 로 상품 정보를 가져올 수 없는 플랫폼.
        // 2026-06-16 dev 실측: KREAM=500(no body)·쿠팡=403·네이버 쇼핑=418, 네이버 스토어=CAPTCHA(429/490).
        // 2026-06-18 dev 실측 추가: 올리브영(www·m)=전 UA(Chrome·iPhone·Android·Googlebot·facebookexternalhit) 403
        //   JS 챌린지("잠시만 기다려 주세요"). 공유 단축 oy.run 은 브라우저 UA 엔 상품정보 없는 제너럴 페이지,
        //   봇 UA 엔 m.oliveyoung(403)로 리다이렉트 — 어느 경로로도 상품 데이터(name·price)가 0이라 LLM 도 불가.
        //   (oy.run 단축은 olive young 의 별도 도메인이라 naver.me 처럼 따로 나열한다.)
        // 2026-06-18 dev 실측 추가: 에이블리(a-bly.com)=Chrome UA fetch 로 m.a-bly·applink 모두 403 "보안 확인 중".
        //   모바일/봇 UA 로 공유 OG(이름+이미지)는 받으나 가격이 OG·실페이지(전 UA 403) 어디에도 없어 가격 필수인 추출 불가.
        //   m.a-bly·www·applink 가 a-bly.com 서브도메인이라 통째로 막는다. (헤드리스로 이름+이미지는 닿을 수 있어 그때 목록에서 빼면 됨.)
        // UA·풀 브라우저 헤더·모바일 어느 조합도 우회 안 됨(헤드리스 브라우저·공식 API 영역). 등록 시점에
        // verifySupportedPlatform 으로 거른다 — 담아봐야 파싱이 무의미하게 실패하고 사용자에겐 "주소를 다시 확인"
        // 이라는 틀린 안내가 나가기 때문. host 가 항목과 같거나 그 서브도메인이면 미지원(부분 문자열 매칭이 아니라
        // 도메인 단위라 navermart.com 같은 무관 도메인은 안 걸린다). 네이버는 쇼핑 서브도메인이 전부 차단이고
        // 비쇼핑(블로그·카페)은 애초에 상품 페이지가 아니라, 서브도메인을 나열하지 않고 naver.com 을 통째로 막는다
        // (naver.me 단축은 별도 도메인이라 따로). 차단이 풀리면(특히 네이버는 변동적) 목록에서 뺀다.
        private val UNSUPPORTED_HOSTS =
            setOf(
                "kream.co.kr",
                "coupang.com",
                "naver.com",
                "naver.me",
                "oliveyoung.co.kr",
                "oy.run",
                "a-bly.com",
            )

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
