package com.depromeet.piki.common.ratelimit

// 한도 판정 결과. 거부는 두 축으로 갈린다 — 요청자 몫이 소진된 것(429)과 서비스 전체 가용량이 소진된 것(503)은
// 원인도 응답도 다르므로 타입에서 구분한다. 거부일 때만 재시도 시점을 들어, "허용인데 retryAfter 가 0" 같은
// 무의미한 상태를 타입에서 없앤다.
sealed interface ItemQuotaVerdict {
    // capacityUsed — 차감 후 전역 카운터 누적값. 경고선(#927 의 80%)을 이번 요청이 처음 넘겼는지 가리는 데 쓴다.
    data class Allowed(
        val capacityUsed: Long,
    ) : ItemQuotaVerdict

    // retryAfterSeconds — 창이 리셋되기까지 남은 시간(초, 올림). Retry-After 헤더로 그대로 나간다.
    data class OwnerExceeded(
        val retryAfterSeconds: Long,
    ) : ItemQuotaVerdict

    data class CapacityExceeded(
        val retryAfterSeconds: Long,
    ) : ItemQuotaVerdict
}
