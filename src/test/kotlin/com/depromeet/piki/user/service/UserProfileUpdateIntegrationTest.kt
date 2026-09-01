package com.depromeet.piki.user.service

import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.tournament.domain.TournamentUser
import com.depromeet.piki.tournament.repository.TournamentUserRepository
import com.depromeet.piki.user.domain.UserException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.assertFailsWith

// #690 의 핵심 검증: 프로필 수정에서 닉네임과 무관한 DB 위반은 거짓 409(닉네임 중복)로 숨기지 않고
// 원본 예외 그대로(→500) 드러낸다. 이전엔 catch 가 모든 DataIntegrityViolationException 을 duplicateNickname
// 으로 바꿔, 닉네임 무관 위반이 나도 거짓 사유가 나가고 진짜 서버 버그가 500 으로 안 드러났다.
//
// profile_image 컬럼은 VARCHAR(2048)이고 updateProfileImage 는 도메인 검증이 없어, 2048자를 넘는 URL 이
// saveAndFlush 에서 닉네임 unique 가 아닌 DataIntegrityViolationException("Data too long")을 일으킨다.
// 정상 경로에선 이 URL 이 우리 S3 업로드 결과라 클라가 길이를 못 정하므로, 이 위반이 나면 서버 버그(불변식)다
// — guard 가 이를 409 로 숨기지 않고 500 으로 드러내야 한다.
//
// 이 테스트는 fix 의 negative control 이다: guard(isNicknameUniqueViolation 판별)를 무조건 409 로 되돌리면
// updateProfile 이 DataIntegrityViolationException 대신 UserException 을 던져 아래 assertFailsWith 가 깨진다.
@Transactional
class UserProfileUpdateIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var userService: UserService

    @Autowired
    private lateinit var tournamentUserRepository: TournamentUserRepository

    @Test
    fun `프로필 닉네임을 다른 사람의 토너먼트 참여 닉과 같게 바꾸면 409(닉네임 중복)로 막힌다`() {
        // 전역 유일(#1018)의 반대 방향: 프로필 닉 변경도 참여 닉 풀(tournament_users)과 겹치면 안 된다.
        // "라떼왕"은 어느 유저 프로필에도 없는 참여 닉 전용 값이라, users 풀이 아니라 참여 닉 풀 검사가 막는 것을 격리한다.
        val other = UUID.randomUUID()
        tournamentUserRepository.save(TournamentUser(tournamentId = 1L, userId = other, nickname = "라떼왕"))
        val me = userService.createGuestWithNickname("q${UUID.randomUUID().toString().take(4)}").id

        assertFailsWith<UserException> {
            userService.updateProfile(me, "라떼왕", null)
        }
    }

    @Test
    fun `닉네임 무관 DB 위반(profileImage 길이 초과)은 409 로 오분류되지 않고 원본 예외로 드러난다`() {
        val userId = userService.createGuestWithNickname("q${UUID.randomUUID().toString().take(4)}").id
        // profile_image VARCHAR(2048) 초과 → 닉네임 unique 가 아닌 DB 제약 위반
        val tooLongUrl = "https://x/" + "a".repeat(2048)

        // 닉네임 unique 위반이 아니므로 duplicateNickname(409, UserException)으로 변환되지 않고
        // 원본 DataIntegrityViolationException 이 그대로 전파돼야 한다. guard 를 무조건 409 로 되돌리면
        // 여기서 UserException 이 던져져 이 단언이 실패한다.
        assertFailsWith<DataIntegrityViolationException> {
            userService.updateProfile(userId, null, tooLongUrl)
        }
    }
}
