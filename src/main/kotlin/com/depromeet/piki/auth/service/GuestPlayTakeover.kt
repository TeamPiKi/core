package com.depromeet.piki.auth.service

import com.depromeet.piki.tournament.domain.TournamentUser
import com.depromeet.piki.tournament.repository.TournamentItemRepository
import com.depromeet.piki.tournament.repository.TournamentRepository
import com.depromeet.piki.tournament.repository.TournamentUserRepository
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// 게스트로 하던 플레이를 방금 로그인한 회원 계정으로 옮긴다(#1081).
//
// 왜 필요한가: 소셜 로그인은 세 갈래인데, "그 소셜에 이미 회원 계정이 있음" 갈래는 게스트 userId 를 버리고
// 기존 회원으로 로그인한다(SocialAccountService.resolveUser 1번). 게스트 승격 갈래와 달리 userId 가 바뀌므로
// 게스트로 만든 참여 행이 버려진 계정에 남아, 결과 화면이 "참여자가 아님"(403)이 된다.
// 마스킹(#1060)이 그 화면에서 로그인을 유도하므로, 유도에 응한 사람이 정확히 이 지점에서 막힌다.
//
// 왜 auth 패키지가 tournament 리포지토리를 직접 잡나: 탈퇴 cascade(WithdrawalPersistenceService)가 wish·알림·기기
// 리포지토리를 직접 잡는 것과 같은 결이다. 계정 생애 이벤트가 여러 도메인의 행을 한 트랜잭션으로 옮기는 자리다.
//
// 별도 빈인 이유는 SocialAccountWriter 와 같다 — 호출자(resolveUser)가 비트랜잭션이라 self-invocation 이면
// proxy 를 안 거쳐 @Transactional 이 무력화된다.
@Service
class GuestPlayTakeover(
    private val userRepository: UserRepository,
    private val tournamentRepository: TournamentRepository,
    private val tournamentUserRepository: TournamentUserRepository,
    private val tournamentItemRepository: TournamentItemRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 게스트의 토너먼트 참여와 담은 아이템을 회원에게 옮긴다. 옮긴 참여 행 수를 돌려준다.
    //
    // 옮기는 것은 tournament_users(참여·진행·완주·참여 닉)와 tournament_items.user_id(담은 사람) 둘뿐이다.
    // - FCM(user_devices)은 옮기지 않는다. UserDeviceWriter 가 같은 fcm_token 을 들고 있던 이전 행을 지우고
    //   새 주인에게 넘기므로, 로그인 후 앱이 재등록하면 저절로 정리된다.
    // - 지표(user_daily_activity)는 옮기면 안 된다. PK(user_id, active_date) 충돌도 나지만, 더 큰 문제는
    //   "게스트로 활동하다 회원이 됐다" 는 사실 자체가 전환 지표의 데이터라 이미 집계된 과거 수치가 사후에 바뀐다.
    // - 알림(notifications)은 이번 범위 밖이다. 과거분이라 가치가 작아 손볼 면적만 늘린다.
    @Transactional
    fun takeOver(
        guestId: UUID,
        memberId: UUID,
    ): Int {
        if (guestId == memberId) return 0
        // 게스트 세션이 맞는지 확인한다. 회원 토큰을 들고 다른 회원으로 로그인하는 경우까지 데이터를 옮기면
        // 멀쩡한 계정의 플레이가 빨려 나간다 — 승계는 "버려지는 게스트" 에 한정한다.
        val guest = userRepository.findById(guestId) ?: return 0
        if (guest.identityType != IdentityType.GUEST) return 0
        guest.deletedAt?.let { return 0 }

        val guestTUs = tournamentUserRepository.findByUserId(guestId)
        if (guestTUs.isEmpty()) return 0

        // 회원이 이미 자리를 잡은 토너먼트는 건너뛴다 — uk_tournament_users(tournament_id, user_id) 충돌이다.
        // 한 사람이 같은 방에 회원·게스트로 두 번 참여한 비정상 상태라, 회원 행을 정본으로 두고 게스트 행은 접는다.
        // 병합(진행이 앞선 쪽 살리기)은 히스토리 재지향까지 필요해 비용이 이득을 넘는다.
        val occupied = tournamentUserRepository.findTournamentIdsByUserIdIncludingDeleted(memberId).toSet()
        val (conflicting, movableTUs) = guestTUs.partition { it.tournamentId in occupied }

        val moved = tournamentUserRepository.transferToUser(guestId, memberId, movableTUs.map { it.tournamentId })
        // 접기 전에 방장 자리를 넘긴다. 방장 지정은 tournaments.owner_tournament_user_id 가 참여 행 id 를 직접
        // 가리키는 구조라, 게스트가 방장인 방에서 그 행을 접으면 토너먼트가 방장을 영구히 잃는다 (CodeRabbit).
        val foldable = handOverHostSeat(conflicting, memberId)
        tournamentUserRepository.softDeleteByUserIdAndTournamentIds(guestId, foldable)
        // 아이템은 건너뛴 토너먼트 것까지 함께 옮긴다. 담은 사람은 결국 같은 사람이고, 유니크 키에 user_id 가
        // 없어 충돌하지 않는다. 남겨 두면 그 상품의 주인이 버려진 게스트를 가리켜 itemCount 가 어긋난다.
        val movedItems = tournamentItemRepository.transferToUser(guestId, memberId)

        // 계정 생애 이벤트라 정상 흐름도 남긴다(승격 로그와 같은 결). 건너뛴 방이 있으면 함께 드러낸다.
        log.info(
            "게스트 플레이 승계 guestId={} memberId={} 참여이관={} 아이템이관={} 건너뜀={}",
            guestId,
            memberId,
            moved,
            movedItems,
            conflicting.size,
        )
        return moved
    }

    // 충돌한 방 중 게스트 행이 방장인 곳의 방장 자리를 회원 행으로 넘기고, 접어도 되는 토너먼트 id 를 돌려준다.
    //
    // 게스트도 토너먼트를 만들 수 있어(회원 전용 게이트가 임시 해제된 상태) 게스트 참여 행이 방장일 수 있다.
    // 방장 판정은 전부 "내 참여 행 id == tournaments.owner_tournament_user_id" 라, 그 행을 접으면 아무도
    // 방장이 아니게 되어 시작·플레이링크·아이템 삭제가 전부 막힌 방이 남는다. FK·트리거가 없어 DB 도 안 잡아준다.
    //
    // 회원의 활성 참여 행이 없으면(회원도 그 방을 떠난 상태) 넘길 자리가 없다. 그때는 게스트 방장 행을 접지 않고
    // 남겨 방장을 유지한다 — 같은 사람이 둘로 보이는 것보다 방장 없는 방이 더 나쁘다.
    private fun handOverHostSeat(
        conflicting: List<TournamentUser>,
        memberId: UUID,
    ): List<Long> {
        if (conflicting.isEmpty()) return emptyList()
        val memberTUByTournamentId = tournamentUserRepository
            .findByTournamentIds(conflicting.map { it.tournamentId })
            .filter { it.userId == memberId }
            .associateBy { it.tournamentId }
        // 충돌 방은 보통 0~1개라 단건 조회 루프로 둔다 — 배치 조회를 새로 뚫을 만큼의 N 이 아니다.
        return conflicting.mapNotNull { guestTU ->
            val tournament = tournamentRepository.findTournamentById(guestTU.tournamentId) ?: return@mapNotNull null
            if (tournament.ownerTournamentUserId != guestTU.getId()) return@mapNotNull guestTU.tournamentId
            val memberTU = memberTUByTournamentId[guestTU.tournamentId]
                ?: run {
                    log.warn(
                        "게스트가 방장인 방에 회원 활성 참여 행이 없어 방장 자리를 못 넘긴다 — 게스트 행을 남긴다 tournamentId={}",
                        guestTU.tournamentId,
                    )
                    return@mapNotNull null
                }
            tournament.assignOwner(memberTU.getId())
            tournamentRepository.saveTournament(tournament)
            log.info("게스트 방장 자리를 회원에게 넘김 tournamentId={} memberId={}", guestTU.tournamentId, memberId)
            guestTU.tournamentId
        }
    }
}
