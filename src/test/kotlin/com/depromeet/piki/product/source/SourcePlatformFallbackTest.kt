package com.depromeet.piki.product.source

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals

class SourcePlatformFallbackTest {
    @ParameterizedTest
    @CsvSource(
        "www.musinsa.com, musinsa",
        "musinsa.com, musinsa",
        // 2단 public suffix(co.kr)를 PSL 이 알아 "co" 가 아니라 "29cm" 가 나온다 — 순진한 TLD 자르기와의 차이.
        "shop.29cm.co.kr, 29cm",
        "29cm.co.kr, 29cm",
        "m.a-bly.com, a-bly",
        "global.oliveyoung.com, oliveyoung",
        // PSL private 섹션의 커머스 호스팅 suffix — 등록 가능 도메인이 brand.myshopify.com 이라 입점 브랜드 라벨이 나온다.
        "brand.myshopify.com, brand",
        // 문서화된 한계: PSL 에 없는 호스팅 suffix(cafe24.com)는 입점 브랜드가 아니라 호스팅사 라벨로 떨어진다
        // (host 문자열만으로는 어느 라벨이 브랜드인지 판별 불가). 이런 잔여는 백오피스 수동 등록이 덮는다.
        "brand.cafe24.com, cafe24",
    )
    fun `등록 가능 도메인의 첫 라벨을 임시 표시명으로 유도한다`(
        host: String,
        expected: String,
    ) {
        assertEquals(expected, SourcePlatformFallback.of(host))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            // IP 리터럴 — 도메인 문법이 아니다.
            "192.168.0.10",
            // 단일 라벨 host — public suffix 아래가 아니다.
            "localhost",
            // public suffix 자체 (ICANN·private 섹션) — 등록 가능 도메인이 없다.
            "co.kr",
            "myshopify.com",
        ],
    )
    fun `도메인 문법이 아니거나 등록 가능 도메인이 없으면 host 원형을 그대로 쓴다`(host: String) {
        assertEquals(host, SourcePlatformFallback.of(host))
    }
}
