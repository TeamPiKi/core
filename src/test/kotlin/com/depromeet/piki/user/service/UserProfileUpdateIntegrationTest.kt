package com.depromeet.piki.user.service

import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.tournament.domain.TournamentUser
import com.depromeet.piki.tournament.repository.TournamentUserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.assertEquals

// 프로필 닉네임 중복 허용(#1287)의 계약을 고정한다. 이전엔 전역 유일(#1018)이라 프로필 닉 변경이 다른 사람의
// 프로필 닉·토너먼트 참여 닉 양쪽과 겹치면 409 였다. 이제 어느 쪽과 겹쳐도 통과한다.
//
// 참여 닉 풀과의 교차 검사가 사라진 것을 따로 보는 이유: 그 검사는 user 모듈이 소유한 포트를
// tournament 모듈이 구현하는 구조(NicknameReservationChecker)로 들어가 있어, 포트째 지웠다는 사실이
// 동작으로 드러나야 한다.
//
// #690 이 두던 테스트(닉네임 무관 DB 위반을 409 로 오분류하지 않는다)는 함께 지웠다. 그 오분류는
// "모든 DataIntegrityViolationException 을 duplicateNickname 으로 바꾸던" 변환에서 나왔고,
// 중복 허용으로 그 변환 자체가 사라져 오분류할 대상이 없다.
//
// **이 파일이 검증하는 것은 애플리케이션 검사의 소멸까지다.** 클래스 레벨 @Transactional 자동 롤백이라
// UPDATE 가 flush 되지 않아 DB 제약은 건드리지 않는다 — 실측으로 확인했다(마이그레이션을 빼도 이 두 건은
// 통과한다). uq_users_nickname 이 정말 사라졌는지는 커밋을 내는
// UserProfileUpdateConcurrencyIntegrationTest 가 가드한다.
@Transactional
class UserProfileUpdateIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var userService: UserService

    @Autowired
    private lateinit var tournamentUserRepository: TournamentUserRepository

    @Test
    fun `프로필 닉네임을 다른 사람의 토너먼트 참여 닉과 같게 바꿔도 통과한다`() {
        // "라떼왕"은 어느 유저 프로필에도 없는 참여 닉 전용 값이라, users 풀이 아니라 참여 닉 풀 검사가
        // 사라졌음을 격리해 본다.
        val other = UUID.randomUUID()
        tournamentUserRepository.save(TournamentUser(tournamentId = 1L, userId = other, nickname = "라떼왕"))
        val me = userService.createGuestWithNickname("q${UUID.randomUUID().toString().take(4)}").id

        val updated = userService.updateProfile(me, "라떼왕", null)

        assertEquals("라떼왕", updated.nickname)
    }

    @Test
    fun `프로필 닉네임을 다른 사람의 프로필 닉과 같게 바꿔도 통과한다`() {
        val taken = "q${UUID.randomUUID().toString().take(4)}"
        userService.createGuestWithNickname(taken)
        val me = userService.createGuestWithNickname("q${UUID.randomUUID().toString().take(4)}").id

        val updated = userService.updateProfile(me, taken, null)

        assertEquals(taken, updated.nickname)
    }
}
