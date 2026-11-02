package com.depromeet.piki.wishlist.domain

// 위시 검색어. 화면에 보이는 상품명(사용자가 고친 이름 포함)과 대소문자 없이 부분 일치로 비교한다.
// 상품명은 위시에 저장돼 있지 않고 카드를 계산해야 나오므로, 비교는 DB 가 아니라 카드 계산 뒤에 한다.
@JvmInline
value class WishSearchQuery private constructor(
    val value: String,
) {
    // 상품명이 아직 없는 카드(파싱 중·실패)는 찾지 않는다
    fun matches(name: String?): Boolean = name?.contains(value, ignoreCase = true) ?: false

    companion object {
        // 안 보냈으면 검색하지 않는다(null). 보냈는데 앞뒤 공백을 빼고 비었으면 계약 위반(400).
        fun of(raw: String?): WishSearchQuery? {
            raw ?: return null
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) throw WishException.invalidSearchQuery()
            return WishSearchQuery(trimmed)
        }
    }
}
