package com.depromeet.piki.item.service

import io.micrometer.core.instrument.MeterRegistry

// 파싱 단건의 종결 결과(READY/FAILED)를 result·reason 라벨로 센다 — 추출 실패가 트래픽에서 얼마나·왜 나는지 관측한다(#506).
// 메트릭 이름·라벨 키를 한 곳에 고정해 여러 emit 경로(워커·recover)가 같은 키 집합을 쓰게 한다: 키가 어긋나면
// Prometheus 가 뒤 시계열을 조용히 드롭하기 때문(#465 product.extract 사건). 모든 호출이 result·reason 둘 다 채운다.
// host 는 등록 URL 마다 달라 카디널리티가 무한히 커질 수 있어 라벨에 넣지 않는다 — 호스트별 실패는 로그(safeLogString)로 본다.
object ItemParsingMetrics {
    const val METRIC = "item.parsing"
    const val TAG_RESULT = "result"
    const val TAG_REASON = "reason"

    const val RESULT_READY = "ready"
    const val RESULT_FAILED = "failed"

    // 성공.
    const val REASON_NONE = "none"

    // 워커 확정 실패 — 상품 페이지 아님·추출값 신뢰 불가(ProductSnapshotException). 클라이언트 입력 계약 위반.
    const val REASON_NOT_PRODUCT = "not_product"

    // 워커 확정 실패 — 재시도 무의미한 외부 오류(호스트 차단·4xx 접근 불가·redirect 비정상·Gemini 영구 오류).
    // ErrorCategory 가 RETRYABLE 이 아니라 즉시 종결한 경우다. not_product(상품 아님)와 구분해 별도로 센다.
    const val REASON_PERMANENT_ERROR = "permanent_error"

    // 추출은 됐으나 READY 전이가 값 검증에 막힘(이름 없음 등).
    const val REASON_READY_REJECTED = "ready_rejected"

    // recover — 일시 오류 재실행이 상한을 소진. "우리 파이프라인이 끝내 못 끝낸" 진짜 실패라 가장 주목할 reason.
    const val REASON_RETRY_EXHAUSTED = "retry_exhausted"

    // recover — 되살릴 입력이 없음(link·imageKey 둘 다 부재인 orphan). 이미지도 S3 raw 로 durable 적재되므로 정상 흐름엔 없고,
    // 도달하면 영속화 경로가 깨진 신호다.
    const val REASON_NO_SOURCE = "no_source"

    // recover — 마감(created_at 기준 상한) 초과로 종결. attempt 예산과 무관한 벽시계 판정이라, 박동이 멀쩡한 느린 실행과
    // 슬롯이 없어 집히지 못한 PENDING 이 여기로 온다. 이 reason 이 늘면 용량 부족(또는 외부가 느려짐)의 신호다.
    const val REASON_DEADLINE = "deadline"

    fun record(
        registry: MeterRegistry,
        result: String,
        reason: String,
    ) {
        registry.counter(METRIC, TAG_RESULT, result, TAG_REASON, reason).increment()
    }
}
