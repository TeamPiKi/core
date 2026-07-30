package com.depromeet.piki.tournament.service.dto

// 매치 기록 결과(#683). 기존 응답은 Completed? 단독이라 다음 매치를 실을 자리가 없어 래퍼로 감쌌다.
//
// nextMatch 가 있으면 클라이언트는 재조회 없이 다음 매치를 그린다.
// null 이면 이 라운드가 끝났다는 뜻이고, 클라이언트는 GET 을 다시 불러 다음 라운드를 받는다.
data class RecordMatchResult(
    val nextMatch: TournamentDetail.MatchDetail?,
    // 결승 매치를 기록한 경우에만 채워진다. 그 외에는 null.
    val completed: TournamentDetail.Completed?,
)
