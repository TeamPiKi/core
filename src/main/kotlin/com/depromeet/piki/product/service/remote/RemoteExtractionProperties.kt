package com.depromeet.piki.product.service.remote

import org.springframework.boot.context.properties.ConfigurationProperties

// 파싱(링크·이미지)을 전담하는 원격 추출 서비스(extractor) 호출 설정.
// @ConfigurationPropertiesScan(PikiApplication)으로 자동 등록된다.
@ConfigurationProperties(prefix = "product.extract.remote")
data class RemoteExtractionProperties(
    // extractor base URL. 운영은 배포(deploy.yml)가, 로컬은 .env 가 주입한다. 기본값이 없어
    // 비면 부팅에서 즉시 실패한다(RemoteExtractionHttpClientConfig — env 누락의 침묵 창 차단).
    val baseUrl: String = "",
    val connectTimeoutMs: Int = 2_000,
    // 회수(#1176 4단계)의 stale 판정보다 작아야 함. 크면 살아 있는 호출의 행을 회수가 다시 집어 중복 발주함
    // read 마다의 상한이라 조금씩 오는 응답은 넘을 수 있음. extractor 가 무상태라 대가는 LLM 1회
    // 이미지 경로도 같은 값. extractor 처리 시간 예산은 extractor repo docs/api-contract.md §3
    val readTimeoutMs: Int = 55_000,
) {
    init {
        // 파싱의 유일 경로라 base-url 없인 모든 파싱이 연결 실패로 위장된다 — 부팅에서 즉시 드러낸다.
        require(baseUrl.isNotBlank()) { "product.extract.remote.base-url 이 비어 있다 — 원격 추출은 유일한 파싱 경로다." }
        require(connectTimeoutMs > 0) { "connect-timeout($connectTimeoutMs ms)은 양수여야 한다 — 0 은 무한 대기다." }
        // 0/음수는 HttpURLConnection 에서 '무한 타임아웃'이라, 상한 검사만 있으면 워커 스레드가 영구 블록될 수 있다.
        require(readTimeoutMs > 0) { "read-timeout($readTimeoutMs ms)은 양수여야 한다 — 0 은 무한 대기다." }
        require(readTimeoutMs < STALE_TIMEOUT_MS) {
            "read-timeout($readTimeoutMs ms)은 회수 stale 판정(60s)보다 작아야 한다 — 중복 발주 방지."
        }
    }

    companion object {
        // TODO: 4단계 회수 도입 시 그쪽 stale 판정 상수와 묶음
        private const val STALE_TIMEOUT_MS = 60_000
    }
}
