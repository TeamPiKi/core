package com.depromeet.piki.product.service.remote

import org.springframework.boot.context.properties.ConfigurationProperties

// 파싱(링크·이미지)을 전담하는 원격 추출 서비스(extractor) 호출 설정.
// @ConfigurationPropertiesScan(PikiApplication)으로 자동 등록된다.
@ConfigurationProperties(prefix = "product.extract.remote")
data class RemoteExtractionProperties(
    // extractor base URL. 운영은 배포(deploy.yml)가, 로컬은 .env 가 주입한다. 기본값이 없어
    // 비면 부팅에서 즉시 실패한다(RemoteExtractionHttpClientConfig — env 누락의 침묵 창 차단).
    val baseUrl: String = "",
    // 둘의 합은 회수 기준보다 작아야 함(ItemParsingScheduler 가 부팅에서 검사)
    val connectTimeoutMs: Int = 2_000,
    // 기본값 정본은 계약(extraction.proto 의 caller_read_timeout_seconds). 이미지 경로도 같은 값
    val readTimeoutMs: Int = ExtractionEndpoints.CALLER_READ_TIMEOUT_MS,
) {
    init {
        // 파싱의 유일 경로라 base-url 없인 모든 파싱이 연결 실패로 위장된다 — 부팅에서 즉시 드러낸다.
        require(baseUrl.isNotBlank()) { "product.extract.remote.base-url 이 비어 있다 — 원격 추출은 유일한 파싱 경로다." }
        require(connectTimeoutMs > 0) { "connect-timeout($connectTimeoutMs ms)은 양수여야 한다 — 0 은 무한 대기다." }
        // 0/음수는 HttpURLConnection 에서 '무한 타임아웃'이라, 상한 검사만 있으면 워커 스레드가 영구 블록될 수 있다.
        require(readTimeoutMs > 0) { "read-timeout($readTimeoutMs ms)은 양수여야 한다 — 0 은 무한 대기다." }
    }
}
