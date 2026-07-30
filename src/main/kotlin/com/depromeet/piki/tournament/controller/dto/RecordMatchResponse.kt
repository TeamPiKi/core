package com.depromeet.piki.tournament.controller.dto

import com.depromeet.piki.tournament.service.dto.RecordMatchResult
import com.fasterxml.jackson.annotation.JsonInclude

// 매치 기록 응답(#683). 이관 전에는 CompletedData? 단독이라 다음 매치를 실을 자리가 없어 래퍼로 감쌌다.
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RecordMatchResponse(
    // 같은 라운드에 남은 다음 매치. null 이면 라운드가 끝났다는 뜻이고,
    // 클라이언트는 GET /tournaments/{id} 를 다시 불러 다음 라운드를 받는다.
    val nextMatch: TournamentDetailResponse.MatchResponse?,
    // 결승 매치를 기록한 경우에만 채워지는 본인 순위 결과.
    val completed: TournamentDetailResponse.CompletedData?,
) {
    companion object {
        fun from(result: RecordMatchResult): RecordMatchResponse =
            RecordMatchResponse(
                nextMatch = result.nextMatch?.let { TournamentDetailResponse.MatchResponse.from(it) },
                completed = result.completed?.let { TournamentDetailResponse.CompletedData.from(it) },
            )
    }
}
