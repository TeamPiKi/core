package com.depromeet.piki.item.service

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.HttpMappable
import com.depromeet.piki.product.service.ExtractionFailureBucket
import com.depromeet.piki.product.service.ProductSnapshotException
import com.depromeet.piki.product.service.remote.ProductExtractorException
import kotlin.test.Test
import kotlin.test.assertEquals

// 확정 실패 예외 → 메트릭 reason 라벨의 분기를 망라한다. 이 라벨이 곧 대시보드·알림의 축이라, 예외가 늘거나
// bucket 배정이 바뀌면 여기가 먼저 깨져야 한다. (카탈로그와의 대조는 ExtractionErrorCatalogTest 가 따로 진다 —
// 여기는 "우리 예외가 어떤 라벨이 되나", 저기는 "그 라벨이 계약의 bucket 과 같나"를 본다.)
class ItemParsingMetricsTest {
    // 확정 실패 예외 전량과 기대 라벨. bucket 5종을 하나씩 대표한다.
    private val classified: List<Pair<BaseException, String>> =
        listOf(
            ProductSnapshotException.notProductPage() to ItemParsingMetrics.REASON_NOT_PRODUCT,
            ProductSnapshotException.noExtractableContent() to ItemParsingMetrics.REASON_UNREADABLE,
            ProductSnapshotException.untrustworthyValue() to ItemParsingMetrics.REASON_EXTRACT_QUALITY,
            ProductExtractorException.blockedByTarget() to ItemParsingMetrics.REASON_BLOCKED,
            ProductExtractorException.permanentFailure() to ItemParsingMetrics.REASON_INTERNAL_ERROR,
        )

    @Test
    fun `확정 실패 예외는 자기 bucket 에 해당하는 reason 으로 집계된다`() {
        classified.forEach { (e, reason) ->
            val code = (e as HttpMappable).errorCode?.code
            assertEquals(reason, ItemParsingMetrics.reasonOf(e), "$code 의 메트릭 reason")
        }
    }

    @Test
    fun `bucket 5종이 서로 다른 reason 으로 빠짐없이 나뉜다`() {
        // 두 bucket 이 같은 라벨로 뭉치면 "늘면 무엇을 하는가"가 다시 섞인다(#936 이 permanent_error 에서 겪은 문제).
        val reasons = classified.map { (e, _) -> ItemParsingMetrics.reasonOf(e) }.toSet()

        assertEquals(ExtractionFailureBucket.entries.size, reasons.size, "bucket 수와 reason 라벨 수가 다르다: $reasons")
    }

    @Test
    fun `분류 밖 예외는 internal_error 로 집계된다`() {
        // 코드 버그성 예외(HttpMappable 아님)·치명적 JVM 오류도 확정 실패 경로로 들어올 수 있다. 이름 없는 실패를
        // 다른 바구니에 섞지 않고 "조사 대상"으로 몰아, 다른 reason 의 추세를 오염시키지 않는다.
        val internalError = ItemParsingMetrics.REASON_INTERNAL_ERROR

        assertEquals(internalError, ItemParsingMetrics.reasonOf(IllegalStateException("boom")))
        assertEquals(internalError, ItemParsingMetrics.reasonOf(NullPointerException()))
        assertEquals(internalError, ItemParsingMetrics.reasonOf(OutOfMemoryError()))
    }

    @Test
    fun `bucket 이 없는 일시 실패 예외가 섞여 들어와도 internal_error 로 둔다`() {
        // 일시 실패는 소유권 반납으로 되살아나 종결 집계에 닿지 않는다 — 여기 닿았다면 재시도 판정이 어긋난 것이라
        // 정상 분류가 아니라 조사 대상이다.
        val transient = ProductExtractorException.transientFailure(RuntimeException("원격 502"))

        assertEquals(ItemParsingMetrics.REASON_INTERNAL_ERROR, ItemParsingMetrics.reasonOf(transient))
    }
}
