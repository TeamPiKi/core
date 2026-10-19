package com.depromeet.piki.tournament.controller.dto

import com.depromeet.piki.tournament.service.dto.AddTournamentItemsFromWish
import jakarta.validation.constraints.Size

data class AddTournamentItemsRequest(
    @field:Size(min = 1, max = 32, message = ITEM_IDS_SIZE_MESSAGE)
    val itemIds: List<Long>,
) {
    fun toAddTournamentItemsFromWish(tournamentId: Long): AddTournamentItemsFromWish =
        AddTournamentItemsFromWish(
            tournamentId = tournamentId,
            itemIds = itemIds,
        )

    // Bean Validation 위반 메시지의 single source. OpenAPI example(TournamentItemApiExamples)이 같은 상수를 참조해
    // "필드 검증 문구가 @field 와 example 두 곳에서 따로 노는" 어긋남을 컴파일 타임에 막는다.
    companion object {
        const val ITEM_IDS_SIZE_MESSAGE = "상품은 1~32개 사이로 선택해 주세요."
    }
}
