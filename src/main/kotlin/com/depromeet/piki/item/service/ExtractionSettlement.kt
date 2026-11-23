package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.ItemStatus

// 추출 결과를 아웃박스에 기록한 결과. 기록되지 않은 두 경우는 정상 흐름이라 예외가 아니다
sealed interface ExtractionSettlement {
    data class Applied(
        val status: ItemStatus,
    ) : ExtractionSettlement

    // 같은 결과가 다시 배달됐거나 기한 안전망이 먼저 닫음
    data object Superseded : ExtractionSettlement

    // 탈퇴로 슬롯 행이 사라져 결과를 받을 곳이 없음
    data object Discarded : ExtractionSettlement
}
