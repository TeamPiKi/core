package com.depromeet.piki.product.service.remote

import org.springframework.boot.context.properties.ConfigurationProperties
import java.util.Locale

// 원격 추출 서비스(PIKI-Extractor) 라우팅 설정. @ConfigurationPropertiesScan(PikiApplication)으로 자동 등록된다.
//
// 스위치 자체는 이 클래스가 아니라 @ConditionalOnProperty("product.extract.remote.enabled") 하나가 진다 —
// 여기에 enabled 필드를 두면 같은 키의 독자가 둘이 되어, relaxed 바인딩("yes"→true)과 조건의 정확 매칭("true"만)이
// 갈라지는 분열이 생긴다(스위치가 조용히 꺼진 채 켜졌다고 믿게 되는 최악의 오설정). 독자를 하나로 줄여 그 분열을 없앤다.
// 이미지 게이트(image-enabled, HttpImageSnapshotExtractor)도 같은 이유로 필드를 두지 않는다.
// enabled=false(기본)면 원격 빈들(라우팅·클라이언트·RestClient)이 아예 뜨지 않아 현행과 완전 동일(zero-diff)이고,
// 전환이 끝나면(이관 8단계) 이 설정과 embedded 경로를 함께 제거한다.
@ConfigurationProperties(prefix = "product.extract.remote")
data class RemoteExtractionProperties(
    // PIKI-Extractor base URL (예: http://10.0.x.x:8090). 원격 빈이 뜰 때(enabled=true) 필수 —
    // 검증은 빈이 실제로 뜨는 자리(RemoteExtractionHttpClientConfig)가 한다.
    val baseUrl: String = "",
    // 원격으로 보낼 host 목록(도메인 단위 suffix 매칭, 서브도메인 포함). 비어 있으면 아무것도 원격으로 가지 않고(안전 기본값),
    // 전량 전환은 "*" 명시로만 연다 — enabled 만 켜고 목록을 깜빡했을 때 100% 컷오버가 터지는 fail-open 을 막는다.
    val hosts: List<String> = emptyList(),
    val connectTimeoutMs: Int = 2_000,
    // 호출자 stale 판정(ItemParsingScheduler.STALE_TIMEOUT_SECONDS=60L)보다 항상 작아야 recover 의 유령 중복 발주가 없다.
    // 한계: SimpleClientHttpRequestFactory 의 read timeout 은 per-read 소켓 타임아웃이라 총 소요시간의 상한은 아니다 —
    // slow-drip 응답(read 마다 55s 미만 간격)은 이 가드를 지나 stale 을 넘길 수 있다. 그 경우에도 extractor 가
    // 무상태라 중복 발주의 대가는 LLM 비용 1회로 바운드된다(상태 오염 없음). extractor 내부 예산과 함께
    // 계약 문서(extractor repo docs/api-contract.md §3)를 갱신한다.
    val readTimeoutMs: Int = 55_000,
) {
    // 매칭에 쓰는 정규형(소문자·trailing dot 제거) — ProductLink.matchesAnyDomain 의 도메인 목록 계약.
    // 외부 env 입력(EXTRACT_REMOTE_HOSTS)의 대소문자·표기 편차가 매칭을 조용히 무산시키지 않게 한 번만 정규화해 둔다.
    val normalizedHosts: List<String> =
        hosts.map { it.trim().trimEnd('.').lowercase(Locale.ROOT) }.filter { it.isNotBlank() }

    init {
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
        // 경계를 건드리는 별도 작업이라 전환기엔 복제+주석으로 둔다.
        private const val STALE_TIMEOUT_MS = 60_000

        // 전량 원격 전환의 명시 마커. hosts 에 이 값이 있으면 모든 host 를 원격으로 보낸다.
        const val ALL_HOSTS = "*"
    }
}
