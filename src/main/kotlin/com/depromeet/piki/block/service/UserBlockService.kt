package com.depromeet.piki.block.service

import com.depromeet.piki.block.domain.UserBlock
import com.depromeet.piki.block.domain.UserBlockException
import com.depromeet.piki.block.repository.UserBlockRepository
import com.depromeet.piki.block.service.dto.BlockList
import com.depromeet.piki.block.service.dto.BlockedUser
import com.depromeet.piki.friendship.repository.FriendshipRepository
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.domain.UserException
import com.depromeet.piki.user.service.UserService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 사용자 차단(#1289). 차단하면 서로의 위시를 볼 수 없다.
 *
 * **효과는 양방향이지만 행은 한 방향이다.** 누가 걸었는지를 알아야 화면이 갈린다(5단계) —
 * 차단한 쪽은 "차단한 사용자입니다", 차단당한 쪽은 비공개 프로필과 같은 화면이다.
 *
 * 차단·해제 모두 멱등이다. 친구 기능(#1288)과 같은 결 — 같은 탭을 두 번 눌러도 실패가 아니다.
 */
@Service
class UserBlockService(
    private val userBlockRepository: UserBlockRepository,
    private val friendshipRepository: FriendshipRepository,
    private val userService: UserService,
) {
    /**
     * 차단. 이미 차단했으면 아무것도 하지 않고 성공한다.
     *
     * **친구 관계를 양방향으로 끊는다.** 차단한 쪽만 끊으면 상대의 친구 목록에 내가 남아,
     * 차단당한 사람이 내 공개 폴더 수를 계속 보게 된다.
     *
     * 두 사용자 행을 먼저 잠근다 — 친구 추가(#1288)와 같은 이유다. 잠그지 않으면 "차단과 친구 추가가
     * 동시에 들어와 차단 뒤에 친구 행이 생기는" 순서가 가능하고, 같은 차단 요청 둘이 유니크 위반으로 떨어진다.
     * 잠금 순서는 id 오름차순으로 고정돼 교착하지 않는다.
     *
     * 상대가 없거나 탈퇴했으면 404 다 — 탈퇴를 409 로 알리면 그 계정이 있었다는 사실이 드러난다(#1288 과 같은 판단).
     */
    @Transactional
    fun block(
        userId: UUID,
        blockedId: UUID,
    ) {
        if (userId == blockedId) throw UserBlockException.selfNotAllowed()
        val locked = userService.lockPairForUpdate(userId, blockedId)
        requireMember(locked[userId] ?: throw UserException.notFound())
        requireActiveTarget(locked[blockedId])
        // 친구 끊기는 차단이 이미 걸려 있어도 돌린다 — 1회차와 2회차의 결과 상태가 같아야 멱등이다
        friendshipRepository.delete(userId, blockedId)
        friendshipRepository.delete(blockedId, userId)
        if (userBlockRepository.exists(userId, blockedId)) return
        userBlockRepository.save(UserBlock(userId = userId, blockedId = blockedId))
    }

    /**
     * 차단 해제. 차단한 적 없어도 성공한다(지울 것이 없을 뿐).
     *
     * **친구 관계를 되살리지 않는다.** 차단이 끊은 것을 해제가 복원하면, 차단당한 쪽은 자기도 모르게
     * 친구가 다시 생긴다. 해제 뒤 프로필에 "친구 추가" 버튼이 보이는 것이 기획의 화면이다.
     *
     * 상대의 존재를 확인하지 않는다 — 이미 탈퇴한 사람에 대한 차단도 거둘 수 있어야 한다.
     */
    @Transactional
    fun unblock(
        userId: UUID,
        blockedId: UUID,
    ) {
        requireMember(userService.findActiveById(userId))
        userBlockRepository.delete(userId, blockedId)
    }

    /**
     * 내가 차단한 사람 목록. 최근 차단순.
     *
     * 닉네임·프로필 이미지만 내려간다 — 그 둘은 항상 공개이고, 차단한 상대의 폴더·위시 수는 볼 수 없다.
     *
     * 탈퇴한 사람은 목록에서 빠진다. 탈퇴 cascade 가 차단 행을 양방향으로 지우므로 정상 흐름에선
     * 여기 닿지 않지만, 그 사이의 짧은 틈이나 과거 데이터를 위해 조회에서도 한 번 거른다.
     */
    @Transactional(readOnly = true)
    fun list(userId: UUID): BlockList {
        requireMember(userService.findActiveById(userId))
        val blockedIds = userBlockRepository.findAllByUserId(userId).map { it.blockedId }
        val usersById = userService.findActiveByIds(blockedIds).associateBy { it.id }
        return BlockList(
            blocked =
                blockedIds.mapNotNull { usersById[it] }.map {
                    BlockedUser(userId = it.id, nickname = it.nickname, profileImage = it.profileImage)
                },
        )
    }

    // 게스트는 차단을 쓸 수 없다. 앱은 이 403 을 받아 로그인 안내 모달을 띄운다
    private fun requireMember(user: User) {
        user.deletedAt?.let { throw UserException.deletedUser() }
        if (user.identityType != IdentityType.MEMBER) throw UserBlockException.guestCannotUse()
    }

    // 상대의 부재와 탈퇴를 같은 404 로 묶는다. 둘을 가르면 "그 계정이 있었다" 가 드러난다
    private fun requireActiveTarget(target: User?) {
        val user = target ?: throw UserException.notFound()
        if (!user.isActive()) throw UserException.notFound()
    }
}
