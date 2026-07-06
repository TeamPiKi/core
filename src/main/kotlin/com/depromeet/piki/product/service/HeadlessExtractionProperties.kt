package com.depromeet.piki.product.service

import org.springframework.boot.context.properties.ConfigurationProperties

// 헤드리스(차단 우회) 추출 설정. @ConfigurationPropertiesScan(PikiApplication)으로 자동 등록된다.
//
// 초기 헤드리스 연동은 REST API 호출 하나로 확정이다(엔드포인트 1개). 응답 형태는 아직 미확정이라 매핑은 구현 때 정한다.
// 그 설정이 살 typed 홈을 미리 둔다 — 지금은 enabled 만 소비되고, 단일 API 의 url·timeout 등은 REST 클라이언트를
// 붙일 때 이 클래스에 필드로 함께 추가한다(그때 값이 실제로 쓰이므로, 소비자 없는 미사용 필드를 미리 만들지 않는다).
@ConfigurationProperties(prefix = "product.extract.headless")
data class HeadlessExtractionProperties(
    // 헤드리스 fallback 스위치. 기본 false — 헤드리스 구현(piki-scraper REST API)이 붙기 전엔 꺼 둬야 하며,
    // 켜면 escalatable 차단 링크가 HeadlessProductLinkExtractor 로 흐른다.
    val enabled: Boolean = false,
)
