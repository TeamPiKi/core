package com.depromeet.piki.product.service.remote

import org.springframework.boot.context.properties.ConfigurationProperties

// 파싱(링크·이미지)을 전담하는 원격 추출 서비스(PIKI-Extractor) 호출 설정.
// @ConfigurationPropertiesScan(PikiApplication)으로 자동 등록된다.
@ConfigurationProperties(prefix = "product.extract.remote")
data class RemoteExtractionProperties(
    // PIKI-Extractor base URL. 운영은 배포(deploy.yml)가, 로컬은 .env 가 주입한다. 기본값이 없어
    // 비면 부팅에서 즉시 실패한다(RemoteExtractionHttpClientConfig — env 누락의 침묵 창 차단).
    val baseUrl: String = "",
    val connectTimeoutMs: Int = 2_000,
    // 호출자 stale 판정(ItemParsingScheduler.STALE_TIMEOUT_SECONDS=60L)보다 항상 작아야 recover 의 유령 중복 발주가 없다.
    // 이미지 경로(HttpImageSnapshotExtractor)도 같은 클라이언트·같은 값을 쓴다 — extractor 내부 이미지 예산
    // (S3 download + Gemini OCR 30s cap + crop + 결과 upload, 최악 40s대)이 이 값 미만이어야 하며,
    // 예산 합의는 계약 문서(extractor repo docs/api-contract.md §3)가 진다.
    // 한계: SimpleClientHttpRequestFactory 의 read timeout 은 per-read 소켓 타임아웃이라 총 소요시간의 상한은 아니다 —
    // slow-drip 응답(read 마다 55s 미만 간격)은 이 가드를 지나 stale 을 넘길 수 있다. 그 경우에도 extractor 가
    // 무상태라 중복 발주의 대가는 LLM 비용 1회로 바운드된다(상태 오염 없음).
    val readTimeoutMs: Int = 55_000,
) {
    init {
        // 파싱의 유일 경로라 base-url 없인 모든 파싱이 연결 실패로 위장된다 — 부팅에서 즉시 드러낸다.
        require(baseUrl.isNotBlank()) { "product.extract.remote.base-url 이 비어 있다 — 원격 추출은 유일한 파싱 경로다." }
        require(connectTimeoutMs > 0) { "connect-timeout($connectTimeoutMs ms)은 양수여야 한다 — 0 은 무한 대기다." }
        // 0/음수는 HttpURLConnection 에서 '무한 타임아웃'이라, 상한 검사만 있으면 워커 스레드가 영구 블록될 수 있다.
        require(readTimeoutMs > 0) { "read-timeout($readTimeoutMs ms)은 양수여야 한다 — 0 은 무한 대기다." }
        require(readTimeoutMs < STALE_TIMEOUT_MS) {
            "read-timeout($readTimeoutMs ms)은 outbox stale 판정(60s)보다 작아야 한다 — recover 유령 중복 발주 방지."
        }
    }

    companion object {
        // ItemParsingScheduler.STALE_TIMEOUT_SECONDS(60L, 초) 를 ms 로 옮긴 복제값. 스케줄러 상수를 직접 참조하면
        // 순환 의존(item→product 가 이미 있는데 product→item 을 더하는 꼴)이라 값을 복제하고 주석으로 결속한다.
        // 한계: 이 결속은 기계 강제가 아니라 주석이라, 스케줄러 쪽 60L 을 바꾸면 여기 60_000 도 함께 봐야 한다
        // (한쪽만 바꾸면 유령 중복 방지 불변식이 조용히 깨진다). 두 값을 공용 상수로 올리는 건 item↔product 패키지
        // 경계를 건드리는 별도 작업이라 복제+주석으로 둔다.
        private const val STALE_TIMEOUT_MS = 60_000
    }
}
