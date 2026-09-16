package com.depromeet.piki.item.domain

// 등록/새로고침 알림 분기를 위시·버전의 created_at 비교(ItemRefreshCompletedHandler)로 추론하던 것을 사실로 대체할 값.
enum class ParseTrigger(
    val description: String,
) {
    REGISTER("등록. 위시·토너먼트 아이템을 처음 담아 생긴 작업"),
    REFRESH("위시 새로고침으로 생긴 작업"),
}
