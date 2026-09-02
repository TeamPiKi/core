package com.depromeet.piki.tournament.service

import com.depromeet.piki.tournament.repository.TournamentUserRepository
import com.depromeet.piki.user.service.NicknameReservationChecker
import java.util.UUID
import org.springframework.stereotype.Component

// user 모듈이 소유한 포트(NicknameReservationChecker)를 tournament 쪽에서 구현한다(#1018).
// 프로필 닉 변경·중복 체크가 토너먼트 참여 닉 풀까지 보게 해 "모든 표시명 전역 유일"을 양방향으로 성립시킨다.
@Component
class TournamentNicknameReservationChecker(
    private val tournamentUserRepository: TournamentUserRepository,
) : NicknameReservationChecker {
    override fun isTakenByOtherTournamentUser(
        nickname: String,
        excludeUserId: UUID?,
    ): Boolean =
        excludeUserId
            ?.let { tournamentUserRepository.existsByNicknameExcludingUser(nickname, it) }
            ?: tournamentUserRepository.existsByNickname(nickname)
}
