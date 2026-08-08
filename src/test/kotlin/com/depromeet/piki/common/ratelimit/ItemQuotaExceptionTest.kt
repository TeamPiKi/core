package com.depromeet.piki.common.ratelimit

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
        val exception = ItemQuotaException.exceeded(WishErrorCode.ITEM_QUOTA_EXCEEDED, retryAfterSeconds = 900)

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.httpStatus)
        assertEquals(ErrorCategory.TOO_MANY_REQUESTS, exception.category)
        assertEquals(WishErrorCode.ITEM_QUOTA_EXCEEDED, exception.errorCode)
        assertEquals(WishErrorCode.ITEM_QUOTA_EXCEEDED.message, exception.message)
        assertEquals(900, exception.retryAfterSeconds)
    }

    @Test
    fun `토너먼트 축은 자기 code 와 문구를 쓴다`() {
        // 두 축이 같은 예외 클래스를 공유하되 사용자 대면 문구·code 는 도메인이 소유한다.
        val exception = ItemQuotaException.exceeded(TournamentErrorCode.ITEM_QUOTA_EXCEEDED, retryAfterSeconds = 60)

        assertEquals(TournamentErrorCode.ITEM_QUOTA_EXCEEDED, exception.errorCode)
        assertEquals(TournamentErrorCode.ITEM_QUOTA_EXCEEDED.message, exception.message)
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
