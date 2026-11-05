package com.depromeet.piki.item.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiscountRateTest {
    @Test
    fun `정가나 현재가를 모르면 null 이다`() {
        assertNull(DiscountRate.of(price = 90_000, originalPrice = null))
        assertNull(DiscountRate.of(price = null, originalPrice = 100_000))
    }

    @Test
    fun `정가 대비 비율을 내림으로 계산하고 할인 없으면 0 이다`() {
        assertEquals(10, DiscountRate.of(price = 90_000, originalPrice = 100_000))
        assertEquals(33, DiscountRate.of(price = 20_000, originalPrice = 30_000))
        assertEquals(0, DiscountRate.of(price = 100_000, originalPrice = 100_000))
        assertEquals(100, DiscountRate.of(price = 0, originalPrice = 100_000))
    }

    @Test
    fun `정가가 0 이면 나누지 않고 0 이다`() {
        assertEquals(0, DiscountRate.of(price = 0, originalPrice = 0))
    }
}
