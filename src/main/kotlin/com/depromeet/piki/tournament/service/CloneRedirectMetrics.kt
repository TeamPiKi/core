package com.depromeet.piki.tournament.service

import io.micrometer.core.instrument.MeterRegistry

// 잔재 CLONE id 로 들어온 요청을 센다(#1188). CLONE 제거(#1027) 뒤 클론 행은 옛 URL 을 ROOT 로 잇는 껍데기로만 남아 있다.
// 이 카운터가 prod 에서 충분히 오래 0 이면 껍데기·shim·클론 가드를 지워도(4-2단계) 아무도 404 를 받지 않는다.
// 정상(ROOT id) 요청은 세지 않는다 — 관심사는 "옛 클론 id 가 아직 오는가" 하나다.
// 메트릭 이름·태그 키를 한 곳에 고정해 emit 경로가 같은 키 집합을 쓰게 한다(키 어긋나면 Prometheus 가 시계열을 드롭).
object CloneRedirectMetrics {
    const val METRIC = "tournament.clone_redirect"
    const val TAG_KIND = "kind"

    // 클론 id 를 ROOT 로 해소해 정상 응답한 경우(조회·플레이·닉네임 등).
    const val KIND_RESOLVED = "resolved"

    // 클론 id 로 아이템을 추가·수정·삭제하려다 클론 가드(032·038)에 막힌 경우.
    const val KIND_ITEM_GUARD = "item_guard"

    fun record(
        registry: MeterRegistry,
        kind: String,
    ) {
        registry.counter(METRIC, TAG_KIND, kind).increment()
    }
}
