package com.depromeet.piki.common.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// 아이템 등록 한도 설정. @ConfigurationPropertiesScan(PikiApplication)으로 자동 등록된다.
//
// 축이 둘이고 서로를 대체하지 않는다. **계정별**(#339)은 한 사람이 얼마나 쓸 수 있는지를, **전역**(#927)은
// 서비스 전체가 얼마나 감당하는지를 정한다. 전자는 남용을, 후자는 정상 사용자가 몰리는 상황을 막는다.
//
// 세는 단위는 요청 수가 아니라 **큐에 넣는 item 수**다 — 이미지 등록은 한 요청이 최대 5장이고 장마다 추출이
// 따로 돌므로, 요청 수로 세면 링크 1건과 이미지 5장이 같은 비용으로 취급돼 실제 소비가 5배까지 벌어진다.
//
// 기준은 "LLM 을 타는가" 가 아니라 **"외부에 돈이 나가는가"** 다. 등록 1건은 파싱이 파서로 풀려 LLM 을 안 타도
// fetch 대역·residential proxy 요청(HEADLESS_FIRST 사이트)·헤드리스 렌더러 시간·이미지 저장·DB 행 영구 증가를
// 소모한다. 그래서 경로별 차등 없이 균일하게 1 을 센다 — 애초에 등록 시점엔 파서로 풀릴지 LLM 으로 갈지 알 수 없고,
// 사이트가 마크업을 바꾸면 어제 파서로 풀리던 링크가 오늘 LLM 을 탄다.
//
// 실제 소비량에 맞춘 정밀 차감(LLM 을 탔는지·프록시 IP 를 몇 번 돌렸는지를 파싱 후에 세는 사후 정산)은 후속 과제다.
//
// 판정은 잔액 방식이다 — 남은 몫이 있으면 요청 크기와 무관하게 통과시키고, 넘긴 만큼은 다음 요청이 갚는다.
// 그래서 창당 실제 소비는 한도가 아니라 (한도 + 1회 최대 요청량)까지 갈 수 있다(RedisItemQuotaStore 주석 참고).
//
// 창은 고정 윈도우(fixed window)다. 첫 차감 시점부터 window 동안이 한 창이고 TTL 만료로 리셋된다.
// 창 경계에서 최대 2배 버스트가 가능하지만(창 끝 + 다음 창 시작), 목적이 "한 계정이 시간당 대략 N개"라
// 그 정도 오차는 비용 방어에 영향을 주지 않는다. 정확한 평활화가 필요해지면 sliding window 로 올린다.
// 모든 사용자가 같은 시각에 리셋되는 창 인덱스 방식은 쓰지 않는다(thundering herd) — 사용자별로 창이 어긋난다.
@ConfigurationProperties(prefix = "item-quota")
data class ItemQuotaProperties(
    // 끄면 차감·판정을 통째로 건너뛴다. 한도가 잘못 잡혀 정상 사용자를 막을 때 배포 없이 되돌리는 스위치다.
    val enabled: Boolean = true,
    val window: Duration = Duration.ofHours(1),
    // 위시 등록 — 요청자 본인이 차감 주체다. 이미지 등록(최대 5장) 2번 또는 링크 10건에 해당한다.
    val wishLimit: Int = 10,
    // 토너먼트 아이템 등록 — 오너 한 명의 몫을 참여자 전원(최대 8명, 게스트 포함)이 나눠 쓴다.
    // 위시보다 크게 두는 이유가 여기 있다: 같은 값이면 친구들이 넣은 만큼 오너가 체감하게 된다.
    // "체감 완화" 를 차감 가중치(예: 0.5)로 풀지 않는 이유는 실제 비용과 카운터가 어긋나면 메트릭으로
    // 실제 호출량을 읽을 수 없게 되기 때문이다 — 차감은 1:1 로 정직하게 두고 한도로 조절한다.
    val tournamentLimit: Int = 30,
    // 전역 가용량 상한(#927) — 계정별 한도 위에 얹는 총량이다. 계정별은 "한 사람이 100번" 을 막지만
    // "100명이 각자 10번" 은 막지 못한다. 비용 방어가 아니라 **가용량 선언**이라, 정상 운영에서는 닿지 않아야 하는
    // 마지노선이다. 닿았다면 인기가 아니라 이상 신호로 읽고 원인부터 가른다.
    //
    // 1000 은 계정별 위시 한도(10)를 꽉 채운 사용자 100명분이다. 파싱 워커가 maxPoolSize 8 · queueCapacity 0 이라
    // 동시 처리는 최대 8건이고, 건당 소요를 파서 1~2초에서 헤드리스·LLM 5~20초로 잡으면 이론 처리량이
    // 시간당 1,400건에서 28,000건 사이가 된다(실측이 아니라 timeout 상한에서 잡은 추정). 그 하단 기준으로도 여유가 있다.
    val capacityLimit: Int = 1_000,
    // 상한의 몇 %에서 경고를 남길지. **상한에 닿으면 이미 늦으므로 이 지점이 실질 방어선이다** — 여기서
    // 손 쓸 시간을 벌기 위한 값이지, 도달 자체가 정상이라는 뜻이 아니다.
    val capacityAlertPercent: Int = 80,
) {
    init {
        // 밀리초로 환산해 검사한다 — Redis PEXPIRE 가 ms 단위라, 1ms 미만(예: 500us)은 양수여도 환산 결과가 0 이 되어
        // 창이 즉시 만료된다. 그러면 매 요청이 새 창을 열어 한도가 사실상 무제한이 되는데, 설정만 보면 정상으로 보인다.
        require(window.toMillis() > 0) {
            "item-quota.window($window)는 1ms 이상이어야 한다 — 그 미만은 창이 즉시 만료돼 한도가 무의미해진다."
        }
        require(wishLimit > 0) { "item-quota.wish-limit($wishLimit)은 양수여야 한다 — 0 이면 위시 등록이 통째로 막힌다." }
        require(tournamentLimit > 0) {
            "item-quota.tournament-limit($tournamentLimit)은 양수여야 한다 — 0 이면 토너먼트 아이템 등록이 통째로 막힌다."
        }
        require(capacityLimit > 0) {
            "item-quota.capacity-limit($capacityLimit)은 양수여야 한다 — 0 이면 모든 사용자의 등록이 통째로 막힌다."
        }
        // 상한은 100 까지 허용한다(도달 시점에만 경고). 0 이하면 첫 요청부터, 100 초과면 영원히 안 울려 둘 다 무의미하다.
        require(capacityAlertPercent in 1..100) {
            "item-quota.capacity-alert-percent($capacityAlertPercent)는 1 에서 100 사이여야 한다."
        }
    }

    // 경고선(건수). 정수 나눗셈이라 내림되지만 경고 시점이 한 건 앞당겨질 뿐이라 무해하다.
    val capacityAlertThreshold: Int get() = capacityLimit * capacityAlertPercent / 100

    // 이번 차감이 경고선을 **처음** 넘겼는지. 넘긴 뒤 매 요청마다 경고하면 창이 끝날 때까지 같은 줄이 반복돼
    // 알림이 무뎌지므로, "직전엔 아래였는데 지금은 위" 인 한 건만 참이 된다.
    fun crossedCapacityAlert(
        capacityUsed: Long,
        amount: Int,
    ): Boolean = capacityUsed >= capacityAlertThreshold && capacityUsed - amount < capacityAlertThreshold

    fun limitOf(scope: ItemQuotaScope): Int =
        when (scope) {
            ItemQuotaScope.WISH -> wishLimit
            ItemQuotaScope.TOURNAMENT -> tournamentLimit
        }
}
