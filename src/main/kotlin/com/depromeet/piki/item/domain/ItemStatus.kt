package com.depromeet.piki.item.domain

enum class ItemStatus(
    val description: String,
) {
    // TODO: PENDING·PROCESSING 은 #1176 2단계에서 삭제 예정. 진행 상태는 ItemParseOutboxStatus 가 정본
    PENDING("파싱 대기 또는 진행 중"),
    PROCESSING("파싱 진행 중. 버전에는 더 이상 기록하지 않음"),
    INCOMPLETE("이름·가격·이미지 중 일부만 추출됨. 사용자가 나머지를 채워야 출전 가능"),
    READY("이름·가격·이미지가 모두 있음"),
    FAILED("값을 하나도 얻지 못했거나 추출이 실패함"),
}
