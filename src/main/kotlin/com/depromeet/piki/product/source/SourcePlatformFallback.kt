package com.depromeet.piki.product.source

import com.google.common.net.InternetDomainName

// 백오피스(source_platforms)에 등록되지 않은 도메인의 임시 표시명 유도. Public Suffix List(Guava 번들 스냅샷) 기반으로
// 등록 가능 도메인(eTLD+1)의 첫 라벨을 뽑는다 — shop.29cm.co.kr → "29cm", www.musinsa.com → "musinsa",
// brand.github.io(PSL private 섹션의 호스팅 suffix) → "brand".
// 한계: PSL 에 없는 임의 다단 도메인(muuusinsa.as.as.as.com)은 어느 라벨이 브랜드인지 host 문자열만으로 판별할 수
// 없어 suffix 바로 앞 라벨("as")로 떨어진다 — 이런 잔여는 백오피스 수동 등록이 덮는다(이 설계의 원래 취지).
object SourcePlatformFallback {
    // host 는 정규형(소문자·trailing dot 없음) 전제 — ProductLink.normalizedHost() 가 책임진다.
    fun of(host: String): String {
        val domainName =
            try {
                InternetDomainName.from(host)
            } catch (e: IllegalArgumentException) {
                // IP 리터럴 등 도메인 문법이 아닌 host — 원형이 가장 정직한 임시값이다.
                return host
            }
        // public suffix 아래가 아니면(단일 라벨 host, public suffix 자체 등) 등록 가능 도메인이 없어 유도 불가.
        if (!domainName.isUnderPublicSuffix && !domainName.isTopPrivateDomain) return host
        return domainName.topPrivateDomain().parts().first()
    }
}
