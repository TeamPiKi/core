package com.depromeet.piki.common.exception

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CommonErrorCodeTest {
    @ParameterizedTest
    @EnumSource(CommonErrorCode::class)
    fun `of 는 각 공통 code 의 category 로 자기 자신을 되찾는다 (category ↔ code 1대1)`(errorCode: CommonErrorCode) {
        assertEquals(errorCode, CommonErrorCode.of(errorCode.category))
    }

    @Test
    fun `공통 code 가 없는 category(CONFLICT)는 of 가 null 을 준다 - 충돌은 항상 도메인 사유라 공통 code 를 두지 않는다`() {
        assertNull(CommonErrorCode.of(ErrorCategory.CONFLICT))
    }

    @Test
    fun `CONFLICT 를 제외한 모든 ErrorCategory 는 대응하는 공통 code 가 있다`() {
        ErrorCategory.entries
            .filter { it != ErrorCategory.CONFLICT }
            .forEach { category ->
                assertNotNull(CommonErrorCode.of(category), "$category 에 대응하는 공통 code 가 없다")
            }
    }

    @Test
    fun `공통 code 의 category 는 서로 겹치지 않는다 (of 의 associateBy 가 덮어쓰지 않도록)`() {
        val categories = CommonErrorCode.entries.map { it.category }
        assertEquals(categories.size, categories.toSet().size, "category 가 겹치는 공통 code 가 있다: $categories")
    }

    @Test
    fun `5xx 재시도 방식 3종은 각각 502·503·500 status 로 갈린다`() {
        assertEquals(HttpStatusValues.BAD_GATEWAY, CommonErrorCode.RETRYABLE.category.httpStatus.value())
        assertEquals(HttpStatusValues.SERVICE_UNAVAILABLE, CommonErrorCode.SERVER_BUSY.category.httpStatus.value())
        assertEquals(HttpStatusValues.INTERNAL_SERVER_ERROR, CommonErrorCode.SERVER_ERROR.category.httpStatus.value())
    }

    private object HttpStatusValues {
        const val BAD_GATEWAY = 502
        const val SERVICE_UNAVAILABLE = 503
        const val INTERNAL_SERVER_ERROR = 500
    }
}
