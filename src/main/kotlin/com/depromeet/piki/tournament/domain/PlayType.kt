package com.depromeet.piki.tournament.domain

// 토너먼트 목록의 솔로/소셜 필터 값. 저장 속성이 아니라 참여 결과로 파생되는 현재 상태다(#836).
// - CLONE(sourceTournamentId 값 존재): SOCIAL (남의 소셜 토너먼트에 참여한 인스턴스)
// - ROOT + 참여자 2명 이상: SOCIAL
// - ROOT + 나 혼자: SOLO
// @Enumerated 대상이 아니다 — 어디에도 영속되지 않고 쿼리 필터 파라미터로만 쓴다.
enum class PlayType {
    SOLO,
    SOCIAL,
}
