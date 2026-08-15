package com.depromeet.piki.common.ratelimit

import java.time.Duration

// 지금 이 순간 적용 중인 한도 값 한 벌. env 기본값 위에 백오피스 오버라이드(#934)를 얹은 **결과**다.
//
// 값을 하나씩 꺼내 쓰지 않고 이 불변 객체를 통째로 읽는 이유: 판정 도중 백오피스 저장이 끼어들면 한 요청이
// 옛 user-limit 과 새 capacity-limit 을 섞어 보게 된다. 한 번 받아 끝까지 그것만 쓰면 그런 반쪽 상태가 없다.
data class ItemQuotaSnapshot(
    val enabled: Boolean,
    // 창 길이는 백오피스 조절 대상이 아니다(env 전용) — 바꾸면 이미 돌고 있는 카운터는 옛 TTL 로 만료되고
    // 새 카운터만 새 창을 쓰는데, 사용자마다 창 시작 시점이 달라 중간 상태를 설명할 수 없다.
    val window: Duration,
    val userLimit: Int,
    val capacityLimit: Int,
    val capacityAlertPercent: Int,
) {
    init {
        // 불변식 층이다. env 는 부팅 시 ItemQuotaProperties 가, 백오피스 입력은 AdminItemQuotaService 가 먼저 거른다.
        // 정상 경로로는 여기 닿지 않으며, 닿았다면 어느 경계가 검증을 빠뜨린 것이다.
        require(window.toMillis() > 0) { "window($window)는 1ms 이상이어야 한다 — 그 미만은 창이 즉시 만료돼 한도가 무의미해진다." }
        require(userLimit > 0) { "userLimit($userLimit)은 양수여야 한다." }
        require(capacityLimit > 0) { "capacityLimit($capacityLimit)은 양수여야 한다." }
        require(capacityAlertPercent in 1..100) { "capacityAlertPercent($capacityAlertPercent)는 1 에서 100 사이여야 한다." }
    }

    // 경고선(건수). 정수 나눗셈이라 내림되지만 경고 시점이 한 건 앞당겨질 뿐이라 무해하다.
    val capacityAlertThreshold: Int get() = capacityLimit * capacityAlertPercent / 100

    // 이번 차감이 경고선을 **처음** 넘겼는지. 넘긴 뒤 매 요청마다 경고하면 창이 끝날 때까지 같은 줄이 반복돼
    // 알림이 무뎌지므로, "직전엔 아래였는데 지금은 위" 인 한 건만 참이 된다.
    fun crossedCapacityAlert(
        capacityUsed: Long,
        amount: Int,
    ): Boolean = capacityUsed >= capacityAlertThreshold && capacityUsed - amount < capacityAlertThreshold

    companion object {
        // env 기본값 위에 백오피스 오버라이드를 얹는 단일 지점. 오버라이드가 없거나(행 없음) 그 컬럼이 null 이면
        // 그 노브만 env 값이 남는다 — 부분 오버라이드라 상한 하나만 급히 내릴 때 나머지를 다시 적을 필요가 없다.
        fun of(
            properties: ItemQuotaProperties,
            override: ItemQuotaSettingsEntity? = null,
        ): ItemQuotaSnapshot =
            ItemQuotaSnapshot(
                enabled = override?.enabled ?: properties.enabled,
                window = properties.window,
                userLimit = override?.userLimit ?: properties.userLimit,
                capacityLimit = override?.capacityLimit ?: properties.capacityLimit,
                capacityAlertPercent = override?.capacityAlertPercent ?: properties.capacityAlertPercent,
            )
    }
}
