package com.depromeet.piki.item.service

import com.depromeet.piki.product.service.ProductSnapshotException
import com.depromeet.piki.product.service.remote.ProductExtractorException
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 워커의 실패 분류(재시도 vs 확정)를 순수 함수로 망라한다. 핵심은 HttpMappable 이 아닌 예상 못한 예외를
// 보수적으로 재시도 대상으로 두는 것 — 즉시 FAILED 로 떨어뜨리면 일시 오류를 영구로 오판해 사라진다.
// recover 상한이 무한 재시도를 막으므로 bounded 하다(#461 retry-first 기조).
// (예외 표본은 원격 파싱 경계의 실제 산출물 — 일시 실패는 transientFailure(원격 5xx·연결 실패),
//  확정 실패는 permanentFailure(422)·ProductSnapshotException 이다.)
class AsyncItemParsingWorkerTest {
    @Test
    fun `RETRYABLE 인 HttpMappable 예외는 재시도 대상이다`() {
        assertTrue(AsyncItemParsingWorker.isRetryable(ProductExtractorException.transientFailure(RuntimeException("원격 502"))))
    }

    @Test
    fun `RETRYABLE 이 아닌 HttpMappable 예외는 재시도 대상이 아니다(즉시 확정 실패)`() {
        // 원격 422 의 번역 결과 전부 — bucket 이 무엇이든(상품 아님·못 읽음·값 불신·대상 차단·조사 대상)
        // 재시도 판정은 하나로 같다. reason 재편(#936)이 전이 판정을 건드리지 않았음을 여기서 고정한다.
        assertFalse(AsyncItemParsingWorker.isRetryable(ProductExtractorException.permanentFailure()))
        assertFalse(AsyncItemParsingWorker.isRetryable(ProductExtractorException.blockedByTarget()))
        assertFalse(AsyncItemParsingWorker.isRetryable(ProductSnapshotException.notProductPage()))
        assertFalse(AsyncItemParsingWorker.isRetryable(ProductSnapshotException.untrustworthyValue()))
        assertFalse(AsyncItemParsingWorker.isRetryable(ProductSnapshotException.noExtractableContent()))
    }

    @Test
    fun `HttpMappable 이 아닌 예상 못한 예외는 보수적으로 재시도 대상이다`() {
        assertTrue(AsyncItemParsingWorker.isRetryable(RuntimeException("예상 못한 오류")))
        assertTrue(AsyncItemParsingWorker.isRetryable(IllegalStateException("boom")))
        assertTrue(AsyncItemParsingWorker.isRetryable(NullPointerException()))
    }

    @Test
    fun `치명적 JVM 오류(Error)는 재시도 대상이 아니다`() {
        // runCatching 이 Throwable 을 다 잡아 Error 도 여기로 온다. 재시도해도 소용없으므로 제외한다.
        assertFalse(AsyncItemParsingWorker.isRetryable(OutOfMemoryError()))
        assertFalse(AsyncItemParsingWorker.isRetryable(StackOverflowError()))
    }
}
