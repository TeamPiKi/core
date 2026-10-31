package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.ItemStatus

// 추출 결과를 아웃박스에 기록한 결과. 기록되지 않은 두 경우는 정상 흐름이라 예외가 아니다
sealed interface ExtractionSettlement {
    data class Applied(
        val status: ItemStatus,
    ) : ExtractionSettlement

    // 회수로 이 시도가 행을 놓쳤거나 다른 실행이 이미 끝냄
    data object Superseded : ExtractionSettlement

    // 탈퇴로 슬롯 행이 사라져 결과를 받을 곳이 없음
    data object Discarded : ExtractionSettlement
}
