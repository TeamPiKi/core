package com.depromeet.piki.common.ratelimit

// 한도 판정 결과. 거부일 때만 재시도 시점을 들어, "허용인데 retryAfter 가 0" 같은 무의미한 상태를 타입에서 없앤다.
sealed interface ItemQuotaVerdict {
    data object Allowed : ItemQuotaVerdict

    // retryAfterSeconds — 창이 리셋되기까지 남은 시간(초, 올림). Retry-After 헤더로 그대로 나간다.
    data class Exceeded(
        val retryAfterSeconds: Long,
    ) : ItemQuotaVerdict
}
