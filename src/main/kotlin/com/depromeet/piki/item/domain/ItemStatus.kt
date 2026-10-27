package com.depromeet.piki.item.domain

// 저장하지 않는다. 카드의 아이템·버전·최근 아웃박스에서 계산한다.
enum class ItemStatus(
    val description: String,
) {
    PARSING("아이템이 아직 확정되지 않았고 파싱 중"),
    FAILED("아이템이 확정되지 못한 채 파싱이 실패함"),
    INCOMPLETE("이름·가격·이미지 중 일부만 있음. 사용자가 나머지를 채워야 출전 가능"),
    READY("이름·가격·이미지가 모두 있음"),
}
