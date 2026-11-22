package com.depromeet.piki.friendship.service

import com.depromeet.piki.block.repository.UserBlockRepository
import com.depromeet.piki.friendship.domain.Friendship
import com.depromeet.piki.friendship.domain.FriendshipException
import com.depromeet.piki.friendship.repository.FriendshipRepository
import com.depromeet.piki.friendship.service.dto.FriendList
import com.depromeet.piki.friendship.service.dto.FriendSummary
import com.depromeet.piki.profile.service.ProfileCountReader
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.domain.UserException
import com.depromeet.piki.user.service.UserService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 단방향 친구(#1288). 요청·수락이 없어 추가는 내 목록에만 생기고 상대에게 알림도 가지 않는다.
 *
 * 추가·삭제가 모두 멱등이다 — 앱이 같은 탭을 두 번 보내거나 이미 지운 관계를 또 지워도 같은 성공을 돌려준다.
 * 그 자리에 409 를 두면 사용자가 아무것도 잘못하지 않았는데 실패를 보게 된다.
 */
@Service
class FriendshipService(
    private val friendshipRepository: FriendshipRepository,
    private val userBlockRepository: UserBlockRepository,
    private val userService: UserService,
    private val profileCountReader: ProfileCountReader,
) {
    /**
     * 친구 추가. 이미 친구면 아무것도 하지 않고 성공한다.
     *
     * **두 사용자 행을 먼저 잠근다.** 잠금 없이 확인하고 저장하면 두 가지가 깨진다.
     *  - 확인과 저장 사이에 상대(또는 나)가 탈퇴를 커밋하면, 탈퇴 cascade 가 먼저 돌고 저장이 뒤에 붙어
     *    탈퇴자가 들어간 친구 행이 영영 남는다. 목록 조회가 가려 주지만 "탈퇴 시 양방향 삭제" 가 깨진 상태다.
     *  - 같은 A→B 요청 둘이 동시에 `exists=false` 를 읽으면 뒤쪽 INSERT 가 유니크 위반으로 떨어져,
     *    멱등이어야 할 재요청이 오류를 받는다.
     *
     * 잠금 순서는 [UserService.lockPairForUpdate] 가 id 오름차순으로 고정한다 — A→B 와 B→A 가 동시에
     * 와도 교착하지 않는다. 탈퇴 cascade 도 user 행을 먼저 잠그므로 그쪽과도 맞물린다.
     *
     * save 를 try/catch 로 감싸 중복만 삼키는 방법은 쓰지 않는다. 같은 트랜잭션에서 난 예외가 트랜잭션을
     * 롤백 전용으로 표시해, 삼켜도 커밋에서 터진다.
     *
     * 상대가 없거나 탈퇴했으면 **404** 다. 탈퇴를 409 로 알리면 "그 계정이 있었다" 는 사실이 드러나고,
     * 요청자 입장에선 그냥 찾을 수 없는 상대다. 409 는 내 계정이 탈퇴 상태일 때의 응답으로 남긴다.
     *
     * **어느 한쪽이라도 차단했으면 같은 404 다**(#1289). 차단한 쪽이든 당한 쪽이든 응답이 같아야 한다 —
     * 다른 응답을 주면 "저 사람이 나를 차단했다" 가 드러난다. 차단한 쪽은 자기가 건 것을 이미 알고,
     * 화면에도 친구 추가 대신 차단 해제가 뜬다.
     */
    @Transactional
    fun add(
        userId: UUID,
        friendId: UUID,
    ) {
        // 잠그기 전에 거른다 — 자기 자신이면 잠글 쌍이 아니고, 입력만 보고 판정할 수 있다
        if (userId == friendId) throw FriendshipException.selfNotAllowed()
        val locked = userService.lockPairForUpdate(userId, friendId)
        requireMember(locked[userId] ?: throw UserException.notFound())
        requireActiveTarget(locked[friendId])
        // 차단은 부재·탈퇴와 같은 404 로 묶는다. 위 KDoc 참고
        if (userBlockRepository.existsBetween(userId, friendId)) throw UserException.notFound()
        if (friendshipRepository.exists(userId, friendId)) return
        friendshipRepository.save(Friendship(userId = userId, friendId = friendId))
    }

    /**
     * 친구 삭제. 친구가 아니었어도 성공한다(지울 것이 없을 뿐).
     *
     * 상대의 존재를 확인하지 않는다 — 이미 탈퇴한 사람과의 관계도 끊을 수 있어야 하고,
     * 탈퇴 cascade 가 먼저 지웠으면 지울 행이 없는 것뿐이다.
     */
    @Transactional
    fun remove(
        userId: UUID,
        friendId: UUID,
    ) {
        requireMember(userService.findActiveById(userId))
        friendshipRepository.delete(userId, friendId)
    }

    /**
     * 내 친구 목록. 최근 추가순.
     *
     * 행마다 "공개 폴더 n개 · 위시 n개" 가 붙는데, **프로필 비공개인 친구는 그 수를 세지 않는다**(#1286 노출 규칙).
     * 세고 나서 가리는 게 아니라 아예 묻지 않는다 — 가리는 쪽은 집계 질의에 그 사람을 넣어 두고 화면에서만
     * 감추는 모양이 되어, 나중에 응답을 한 겹 벗기면 수가 새어 나간다.
     *
     * 탈퇴한 친구는 목록에서 빠진다. 탈퇴 cascade 가 친구 행을 양방향으로 지우므로 정상 흐름에선 여기 닿지
     * 않지만, 그 사이의 짧은 틈이나 과거 데이터를 위해 조회에서도 한 번 거른다.
     */
    @Transactional(readOnly = true)
    fun list(userId: UUID): FriendList {
        // 추가·삭제와 같은 게이트를 건다. 게스트에게 빈 목록을 돌려주면 "친구가 없다" 로 읽혀,
        // 로그인하면 쓸 수 있는 기능이라는 사실이 응답에서 사라진다
        requireMember(userService.findActiveById(userId))
        val friendIds = friendshipRepository.findAllByUserId(userId).map { it.friendId }
        val usersById = userService.findActiveByIds(friendIds).associateBy { it.id }
        // 추가순을 유지한 채 탈퇴자만 떨군다
        val friends = friendIds.mapNotNull { usersById[it] }
        val countedIds = friends.filter { it.isProfilePublic }.map { it.id }
        val counts = profileCountReader.countsOf(countedIds)
        return FriendList(
            friendCount = friends.size,
            friends =
                friends.map { friend ->
                    FriendSummary(
                        userId = friend.id,
                        nickname = friend.nickname,
                        profileImage = friend.profileImage,
                        // 비공개면 null — 앱이 수 대신 "비공개" 를 띄운다
                        counts = counts[friend.id],
                    )
                },
        )
    }

    // 게스트는 친구를 만들 수 없다(#1285). 앱은 이 403 을 받아 로그인 안내 모달을 띄운다.
    // 이미 읽은(또는 잠근) user 를 받는다 — 같은 행을 두 번 읽지 않기 위해서다.
    private fun requireMember(user: User) {
        user.deletedAt?.let { throw UserException.deletedUser() }
        if (user.identityType != IdentityType.MEMBER) throw FriendshipException.guestCannotUse()
    }

    // 상대의 부재와 탈퇴를 같은 404 로 묶는다. 둘을 가르면 "그 계정이 있었다" 가 드러난다.
    private fun requireActiveTarget(target: User?) {
        val user = target ?: throw UserException.notFound()
        if (!user.isActive()) throw UserException.notFound()
    }
}
