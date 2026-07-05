package com.depromeet.piki.product.service.http

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// "무조건 폴백" 계약을 팩토리 단위로 고정한다: 영구 실패는 SSRF 를 빼고 전부 escalatable, 일시 오류는 escalate 대상이 아니다.
class PageFetchExceptionTest {
    private val cause = RuntimeException("x")

    @Test
    fun `영구 실패는 SSRF 를 빼고 전부 escalatable 이다`() {
        assertTrue(PageFetchException.clientError(cause).escalatable, "4xx(클로킹 가능)")
        assertTrue(PageFetchException.permanentUpstreamError(cause).escalatable, "500/501(봇방어)")
        assertTrue(PageFetchException.tooManyRedirects().escalatable, "redirect 루프")
        assertTrue(PageFetchException.malformedRedirect().escalatable, "비정상 redirect")
    }

    @Test
    fun `SSRF 차단과 일시 오류는 escalatable 이 아니다`() {
        // SSRF 로 우리가 막은 내부망은 절대 안 뚫는다. 일시 오류(RETRYABLE)는 escalate 가 아니라 재시도 축이다.
        assertFalse(PageFetchException.blockedHost().escalatable, "SSRF 내부망")
        assertFalse(PageFetchException.upstreamError(cause).escalatable, "502/503/504 일시")
        assertFalse(PageFetchException.emptyBody().escalatable, "빈 body 일시")
    }
}
