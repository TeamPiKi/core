package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.ItemStatus
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// 실패율 알림·대시보드(terraform-grafana)는 이 줄을, 메트릭은 카운터를 세므로 둘이 어긋나지 않게 결과는 여기서만 남김
@Component
class ItemParseResultRecorder(
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun recordSettled(
        itemParseOutboxId: Long,
        status: ItemStatus,
        fields: String,
    ) {
        when (status) {
            ItemStatus.READY -> record(itemParseOutboxId, ItemParsingMetrics.RESULT_READY, ItemParsingMetrics.REASON_NONE, fields)
            ItemStatus.INCOMPLETE -> record(itemParseOutboxId, ItemParsingMetrics.RESULT_INCOMPLETE, ItemParsingMetrics.REASON_NONE, fields)
            ItemStatus.FAILED -> recordFailed(itemParseOutboxId, ParseFailureReason.EXTRACT_QUALITY, fields)
            ItemStatus.PARSING -> error("추출 전이가 만들 수 없는 상태 $status")
        }
    }

    fun recordFailed(
        itemParseOutboxId: Long,
        reason: ParseFailureReason,
        fields: String,
    ) {
        record(itemParseOutboxId, ItemParsingMetrics.RESULT_FAILED, reason.metricLabel, fields)
    }

    private fun record(
        itemParseOutboxId: Long,
        result: String,
        reason: String,
        fields: String,
    ) {
        log.info("item.parse.result outbox={} result={} reason={} {}", itemParseOutboxId, result, reason, fields)
        // 라벨 키 집합이 경로마다 다르면 Prometheus 가 뒤 시계열을 조용히 버림(#465). host 는 카디널리티가 무한이라 라벨 대신 로그로 봄
        meterRegistry.counter(METRIC, TAG_RESULT, result, TAG_REASON, reason).increment()
    }

    companion object {
        private const val METRIC = "item.parsing"
        private const val TAG_RESULT = "result"
        private const val TAG_REASON = "reason"
    }
}
