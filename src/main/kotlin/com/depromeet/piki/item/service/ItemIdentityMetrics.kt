package com.depromeet.piki.item.service

import io.micrometer.core.instrument.MeterRegistry

// 상품 정체성 기록(#825)의 관측 카운터. 공유 활성화 전 관측 구간에서 "병합이 얼마나·어떤 모양으로 발생할지"를
// 실데이터로 보는 것이 목적이다 — 특히 canonical_conflict(병합 후보)와 alias_duplicate(재등록 비율)가
// 활성화 단계의 판단 재료다. 라벨 키는 result 하나로 고정한다(키 집합이 갈리면 Prometheus 가 뒤 시계열을
// 조용히 드롭한다 — #465 사건, ItemParsingMetrics 와 같은 규칙). URL·host 는 카디널리티 무한이라 라벨 금지, 로그로 본다.
object ItemIdentityMetrics {
    const val METRIC = "item.identity"
    const val TAG_RESULT = "result"

    // 등록·파싱 완료 시 별칭이 새로 기록됨.
    const val ALIAS_RECORDED = "alias_recorded"

    // 이미 아는 링크 모양(재등록) — 공유 활성화 시 즉시 매칭될 물량의 실측.
    const val ALIAS_DUPLICATE = "alias_duplicate"

    // 이번 호출이 canonical 을 확정함.
    const val CANONICAL_CLAIMED = "canonical_claimed"

    // 이미 같은 값으로 확정돼 있음(재파싱·갱신의 정상 경로).
    const val CANONICAL_ALREADY_SAME = "canonical_already_same"

    // 이미 확정된 canonical 과 이번 귀결점이 다름 — 몰의 URL 구조 변경·단축링크 만료 등. 정체성은 불변이라
    // 첫 확정을 유지하고 관측만 한다. 늘어나면 정규화 규칙 재검토 신호.
    const val CANONICAL_DRIFT = "canonical_drift"

    // 다른 item 이 같은 canonical 을 이미 소유했는데 병합까지 못 간 극단 경합(승자 조회 실패) — 다음 재파싱이 복구.
    const val CANONICAL_CONFLICT = "canonical_conflict"

    // canonical 충돌을 병합(재부모화 + 별칭 이관 + 임시 item 폐기)으로 해소함 — 공유 활성화(#825 3단계)의 핵심 지표.
    const val CANONICAL_MERGED = "canonical_merged"

    // finalUrl 없음(구버전 extractor·이미지 경로) — 확정 건너뜀.
    const val CANONICAL_NO_FINAL_URL = "canonical_no_final_url"

    // 귀결점이 저장 상한(2048자) 초과 — 절단은 거짓 정체성이라 확정·별칭을 건너뜀.
    const val CANONICAL_OVERSIZE = "canonical_oversize"

    // 귀결점이 URL 로 파싱 불가(비정상 리다이렉트 등) — 확정 건너뜀.
    const val CANONICAL_UNPARSABLE = "canonical_unparsable"

    fun record(
        registry: MeterRegistry,
        result: String,
    ) {
        registry.counter(METRIC, TAG_RESULT, result).increment()
    }
}
