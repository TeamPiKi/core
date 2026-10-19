package com.depromeet.piki.tournament.domain

// 솔로/소셜은 tournaments 에 저장된 컬럼이 아니라 참가 결과로 파생되는 현재 상태다.
// 생성 직후엔 SOLO 이고 누군가 참여하면 SOCIAL 이 된다. 참여 경로는 초대 코드(PENDING 에서만, checkJoinable)와
// 플레이 링크(COMPLETED 에서, createFromPlayLink) 둘이라, 완료된 SOLO 도 링크로 누가 들어오면 SOCIAL 이 된다.
//
// 판정은 참가자 수 하나다 (SOLO 와 SOCIAL 은 서로 여집합). 2명 이상이면 SOCIAL, 혼자면 SOLO.
// CLONE 이 있던 시절엔 "사본은 소유자 1명뿐이라 수로 세면 SOLO 로 오분류" 되는 예외 갈래가 있었으나, CLONE 제거(#1027)로
// 모든 참여자가 같은 토너먼트의 참여 행을 가져 수만으로 판정된다.
enum class TournamentPlayType {
    SOLO,
    SOCIAL,
}
