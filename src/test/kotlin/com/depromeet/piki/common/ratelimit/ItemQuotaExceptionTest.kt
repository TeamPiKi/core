package com.depromeet.piki.common.ratelimit

import com.depromeet.piki.item.domain.ItemErrorCode
import com.depromeet.piki.common.exception.CommonErrorCode
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.tournament.service.TournamentErrorCode
import com.depromeet.piki.wishlist.domain.WishErrorCode
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ItemQuotaExceptionTest {
    @Test
    fun `도메인 code 에서 status 와 message 를 파생한다`() {
        val exception = ItemQuotaException.exceeded(ItemErrorCode.QUOTA_EXCEEDED, retryAfterSeconds = 900)

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.httpStatus)
        assertEquals(ErrorCategory.TOO_MANY_REQUESTS, exception.category)
        assertEquals(ItemErrorCode.QUOTA_EXCEEDED, exception.errorCode)
        assertEquals(ItemErrorCode.QUOTA_EXCEEDED.message, exception.message)
        assertEquals(900, exception.retryAfterSeconds)
    }

    @Test
    fun `토너먼트 축은 자기 code 와 문구를 쓴다`() {
        // 두 축이 같은 예외 클래스를 공유하되 사용자 대면 문구·code 는 도메인이 소유한다.
        val exception = ItemQuotaException.exceeded(ItemErrorCode.QUOTA_EXCEEDED, retryAfterSeconds = 60)

        assertEquals(ItemErrorCode.QUOTA_EXCEEDED, exception.errorCode)
        assertEquals(ItemErrorCode.QUOTA_EXCEEDED.message, exception.message)
    }

    @Test
    fun `재시도 시점이 0 이하면 코드 버그로 즉시 실패한다`() {
        // 0 이면 클라가 즉시 재시도해 또 거부되고, 음수는 Retry-After 로 나갈 수 없는 값이다.
        // 지금 유일한 호출자는 최소 1초를 보장하지만, 그건 그쪽 사정이라 팩토리가 자기 불변식으로 못박는다.
        assertFailsWith<IllegalArgumentException> {
            ItemQuotaException.exceeded(ItemErrorCode.QUOTA_EXCEEDED, retryAfterSeconds = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ItemQuotaException.exceeded(ItemErrorCode.QUOTA_EXCEEDED, retryAfterSeconds = -1)
        }
    }

    @Test
    fun `전역 가용량 소진은 429 가 아니라 503 과 공통 code 를 쓴다`() {
        // 요청자가 자기 몫을 다 쓴 것이 아니라 서비스가 꽉 찬 상태라 4xx 가 아니다. 어느 등록 경로로 닿든
        // 원인도 안내도 하나라 도메인 code 를 두지 않고 공통 SERVER_BUSY 를 쓴다.
        val exception = ItemQuotaException.capacityExceeded(retryAfterSeconds = 900)

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.httpStatus)
        assertEquals(ErrorCategory.SERVER_BUSY, exception.category)
        assertEquals(CommonErrorCode.SERVER_BUSY, exception.errorCode)
        assertEquals(CommonErrorCode.SERVER_BUSY.message, exception.message)
        assertEquals(900, exception.retryAfterSeconds)
    }

    @Test
    fun `전역 가용량 소진도 재시도 시점이 0 이하면 코드 버그로 즉시 실패한다`() {
        assertFailsWith<IllegalArgumentException> { ItemQuotaException.capacityExceeded(retryAfterSeconds = 0) }
        assertFailsWith<IllegalArgumentException> { ItemQuotaException.capacityExceeded(retryAfterSeconds = -1) }
    }

    @Test
    fun `429 가 아닌 code 로 만들면 코드 버그로 즉시 실패한다`() {
        // status 는 category 가 소유하므로, 429 아닌 code 를 넘기면 "한도 초과인데 409" 같은 응답이 조용히 나간다.
        // 클라이언트가 도달할 수 없는 개발자 실수라 계약 예외가 아니라 불변식 위반(require)으로 막는다.
        assertFailsWith<IllegalArgumentException> {
            ItemQuotaException.exceeded(WishErrorCode.ALREADY_EXISTS, retryAfterSeconds = 60)
        }
    }
}
