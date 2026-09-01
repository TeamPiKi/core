package com.depromeet.piki.user.service

import java.util.UUID

// 프로필 닉네임이 "모든 표시명 전역 유일" 규칙(#1018)을 지키려면 users 풀뿐 아니라 토너먼트 참여 닉(tournament_users)과도
// 겹치지 않아야 한다. user 모듈이 tournament 모듈에 직접 의존하지 않도록(레이어 역전 방지) 이 포트를 user 가 소유하고,
// tournament 모듈이 구현한다 — 의존 방향 tournament→user 는 이미 존재하므로 새 방향을 만들지 않는다.
interface NicknameReservationChecker {
    // 다른 사람이 이미 이 닉네임을 토너먼트 참여 닉으로 쓰고 있는지. excludeUserId 는 자기 자신을 제외한다
    // (자기 참여 닉과 같은 값 허용 — "자기 이름은 항상 허용"). null 이면 제외 없이(신원 미상 사전 체크) 검사한다.
    fun isTakenByOtherTournamentUser(
        nickname: String,
        excludeUserId: UUID?,
    ): Boolean
}
