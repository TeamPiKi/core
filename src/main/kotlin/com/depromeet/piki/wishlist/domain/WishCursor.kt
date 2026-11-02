package com.depromeet.piki.wishlist.domain

// 위시리스트 cursor 페이지네이션의 커서. 직전 페이지 마지막 항목의 위치를 담는다.
// 위치는 전체 목록이면 위시 id, 폴더로 거른 목록이면 폴더에 넣은 연결의 id 다(폴더 안은 넣은 순서로 보이므로).
// 응답에서 String 으로 내려가고 다음 요청에 그대로 돌아오므로 입력 형식 분기를 여기서 흡수한다.
@JvmInline
value class WishCursor private constructor(
    val position: Long,
) {
    companion object {
        // 비었으면 첫 페이지(null). 숫자로 못 바꾸면 우리가 준 적 없는 값 → 계약 위반(400).
        fun parse(raw: String?): WishCursor? {
            if (raw.isNullOrBlank()) return null
            val id = raw.trim().toLongOrNull() ?: throw WishException.invalidCursor()
            return WishCursor(id)
        }

        fun at(position: Long): WishCursor = WishCursor(position)
    }
}
