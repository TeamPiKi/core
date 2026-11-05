package com.depromeet.piki.item.domain

// 내림이라 표시 할인율이 실제보다 커지지 않는다
object DiscountRate {
    fun of(
        price: Int?,
        originalPrice: Int?,
    ): Int? {
        originalPrice ?: return null
        price ?: return null
        if (originalPrice == 0) return 0
        return ((originalPrice - price).toLong() * 100 / originalPrice).toInt()
    }
}
