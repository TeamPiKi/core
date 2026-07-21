package com.depromeet.piki.user.service

import io.micrometer.core.instrument.MeterRegistry

// 탈퇴 시 Redis 토큰 무효화의 최종 실패(재시도 후에도 실패)를 센다 (#689).
// denylist 마킹을 놓치면 탈퇴 회원의 잔여 access token 이 만료(최대 15분)까지 통과하는 보안 창이 열리므로,
// 이 카운터가 0 이 아니면 알람 대상이다. 정상(성공)은 세지 않는다 — 관심사는 "얼마나 자주 놓치나"다.
// 메트릭 이름·태그 키를 한 곳에 고정해 emit 경로가 같은 키 집합을 쓰게 한다(키 어긋나면 Prometheus 가 시계열을 드롭).
object WithdrawalMetrics {
    const val METRIC = "user.withdrawal.token_invalidation_failure"
    const val TAG_STEP = "step"

    // refresh 토큰(재발급 경로) 삭제 실패. DB deletedAt 이 이중 방어라 위험도는 낮다.
    const val STEP_REFRESH = "refresh"

    // access denylist 마킹 실패 — 재시도 후에도 실패. 보안 창의 핵심.
    const val STEP_MARK_WITHDRAWN = "mark_withdrawn"

    fun record(
        registry: MeterRegistry,
        step: String,
    ) {
        registry.counter(METRIC, TAG_STEP, step).increment()
    }
}
