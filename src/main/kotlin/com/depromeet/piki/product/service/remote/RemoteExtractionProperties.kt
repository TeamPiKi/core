package com.depromeet.piki.product.service.remote

import org.springframework.boot.context.properties.ConfigurationProperties

// 원격 추출 서비스(extractor)로 직접 거는 HTTP 호출 설정. 파싱은 스트림으로 오가고 모델 검증만 이 경로를 쓴다.
// @ConfigurationPropertiesScan(PikiApplication)으로 자동 등록된다.
@ConfigurationProperties(prefix = "product.extract.remote")
data class RemoteExtractionProperties(
    // extractor base URL. 운영은 배포(deploy.yml)가, 로컬은 .env 가 주입한다. 기본값이 없어
    // 비면 부팅에서 즉시 실패한다(RemoteExtractionHttpClientConfig — env 누락의 침묵 창 차단).
    val baseUrl: String = "",
    val connectTimeoutMs: Int = 2_000,
    // 기본값 정본은 계약(extraction.proto 의 caller_read_timeout_seconds)
    val readTimeoutMs: Int = ExtractionEndpoints.CALLER_READ_TIMEOUT_MS,
) {
    init {
        require(baseUrl.isNotBlank()) { "product.extract.remote.base-url 이 비어 있다." }
        require(connectTimeoutMs > 0) { "connect-timeout($connectTimeoutMs ms)은 양수여야 한다 — 0 은 무한 대기다." }
        // 0/음수는 HttpURLConnection 에서 '무한 타임아웃'이라, 상한 검사만 있으면 워커 스레드가 영구 블록될 수 있다.
        require(readTimeoutMs > 0) { "read-timeout($readTimeoutMs ms)은 양수여야 한다 — 0 은 무한 대기다." }
    }
}
