package com.depromeet.piki.product.service

import com.depromeet.piki.product.domain.ProductLink
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// 차단 우회 헤드리스 추출 "전략"의 자리(seam). 정적 HTTP fetch 가 봇 차단에 막히는 플랫폼을, 실제 브라우저를
// 띄우는 별도 스크래퍼(piki-scraper)로 뚫는 경로다. 초기 연동은 REST API 호출 하나로 확정이며(엔드포인트 1개,
// 설정은 HeadlessExtractionProperties), 응답 형태는 아직 미확정이다 — 렌더된 HTML 이면 기존 파서/LLM 에 흘려넣고,
// 구조화 응답이면 직접 매핑한다. 어느 쪽이든 이 전략은 결과를 ProductSnapshot 으로 돌려주므로 진입점 계약은 그대로다.
//
// 지금은 미구현 placeholder다. FallbackProductLinkExtractor 가 product.extract.headless.enabled=false 로 이
// 경로를 꺼 두므로 정상 흐름에선 절대 호출되지 않는다. enabled 를 켠 채 여기 닿았다면 "seam 은 열렸으나 구현이
// 없다" 는 개발/설정 실수다.
//
// 주의: 추출은 async worker(AsyncItemParsingWorker) 전용이라 여기서 던지는 예외는 HTTP 500 이 되지 못한다.
// error() 가 던지는 IllegalStateException 은 HttpMappable 이 아니라 worker 의 isRetryable 이 재시도 대상으로 분류하므로,
// 즉시 FAILED 가 아니라 PROCESSING 유지 후 recover 가 상한(MAX_ATTEMPTS)까지 재시도한 뒤에야 FAILED 로 종결된다.
// 그 오설정이 조용히 묻히지 않도록 매 호출마다 error 로그로 명시 신호를 남긴다(정상 흐름엔 0건이라 스팸이 아니라 반복 알림이다).
// 던지는 것 자체는 유효한 snapshot 을 만들 수 없어서다(불변식 위반 → error). 정상 요청으론 도달 불가라 커스텀 예외가 아니다.
@Component(LinkExtractionStrategy.HEADLESS)
class HeadlessProductLinkExtractor : LinkExtractionStrategy {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun extract(link: ProductLink): ProductSnapshot {
        log.error(
            "headless 추출이 호출됐으나 미구현이다 — product.extract.headless.enabled 를 구현 없이 켠 설정 실수. item 은 FAILED 로 떨어진다. url={}",
            link.safeLogString(),
        )
        error("헤드리스 추출 전략은 아직 구현되지 않았다. product.extract.headless.enabled 를 켜기 전에 구현을 먼저 붙여야 한다.")
    }
}
