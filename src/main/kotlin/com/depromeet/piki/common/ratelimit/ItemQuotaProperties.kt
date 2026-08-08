package com.depromeet.piki.common.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// 아이템 등록 한도(#339) 설정. @ConfigurationPropertiesScan(PikiApplication)으로 자동 등록된다.
//
// 세는 단위는 요청 수가 아니라 **큐에 넣는 item 수**다 — 이미지 등록은 한 요청이 최대 5장이고 장당 LLM 호출이
// 1회씩 붙으므로, 요청 수로 세면 링크 1건과 이미지 5장이 같은 비용으로 취급돼 실제 호출량이 5배까지 벌어진다.
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
) {
    init {
        require(!window.isZero && !window.isNegative) {
            "item-quota.window($window)는 양수여야 한다 — 0 이면 창이 즉시 만료돼 한도가 무의미해진다."
        }
        require(wishLimit > 0) { "item-quota.wish-limit($wishLimit)은 양수여야 한다 — 0 이면 위시 등록이 통째로 막힌다." }
        require(tournamentLimit > 0) {
            "item-quota.tournament-limit($tournamentLimit)은 양수여야 한다 — 0 이면 토너먼트 아이템 등록이 통째로 막힌다."
        }
    }

    fun limitOf(scope: ItemQuotaScope): Int =
        when (scope) {
            ItemQuotaScope.WISH -> wishLimit
            ItemQuotaScope.TOURNAMENT -> tournamentLimit
        }
}
