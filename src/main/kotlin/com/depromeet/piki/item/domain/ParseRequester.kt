package com.depromeet.piki.item.domain

data class ParseRequester(
    val type: ParseRequesterType,
    val id: Long,
) {
    init {
        require(id > 0) { "요청자 id 는 양수여야 한다: $id" }
    }

    companion object {
        fun wish(wishId: Long): ParseRequester = ParseRequester(ParseRequesterType.WISH, wishId)

        fun tournamentItem(tournamentItemId: Long): ParseRequester = ParseRequester(ParseRequesterType.TOURNAMENT_ITEM, tournamentItemId)
    }
}
