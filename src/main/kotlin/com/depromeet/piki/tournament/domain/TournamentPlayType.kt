package com.depromeet.piki.tournament.domain

// 솔로/소셜은 tournaments 에 저장된 컬럼이 아니라 참가 결과로 파생되는 현재 상태다.
// 참여는 PENDING 에서만 가능하므로(checkJoinable) 값이 변할 수 있는 구간도 PENDING 하나이고,
// IN_PROGRESS 이후로는 참가자가 늘지 않아 고정된다. 생성 직후엔 SOLO 이고 누군가 참여하면 SOCIAL 이 된다.
//
// 판정은 두 갈래다 (SOLO 와 SOCIAL 은 서로 여집합).
//   CLONE(sourceTournamentId 있음) → SOCIAL. 남의 토너먼트에 참여한 사본이라 그 자체가 소셜 관계다.
//     CLONE 은 소유자 1명만 tournament_users 행을 가져, 참가자 수 규칙을 그대로 적용하면 SOLO 로 오분류된다.
//   ROOT → 참가자 2명 이상이면 SOCIAL, 혼자면 SOLO.
enum class TournamentPlayType {
    SOLO,
    SOCIAL,
}
