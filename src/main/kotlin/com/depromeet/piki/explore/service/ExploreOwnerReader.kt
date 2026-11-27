package com.depromeet.piki.explore.service

import com.depromeet.piki.block.repository.UserBlockRepository
import com.depromeet.piki.explore.repository.ExploreWishJpaRepository
import com.depromeet.piki.explore.service.dto.ExploreFolderResult
import com.depromeet.piki.explore.service.dto.ExploreOwnerResult
import com.depromeet.piki.friendship.repository.FriendshipRepository
import com.depromeet.piki.profile.domain.ProfileExposure
import com.depromeet.piki.profile.domain.ProfileRelation
import com.depromeet.piki.user.domain.User
import org.springframework.stereotype.Component
import java.util.UUID

// 상세 화면의 "담은 사람" 줄. 위시 상세와 1위 상세가 같은 규칙으로 그린다
@Component
class ExploreOwnerReader(
    private val userBlockRepository: UserBlockRepository,
    private val friendshipRepository: FriendshipRepository,
    private val exploreWishJpaRepository: ExploreWishJpaRepository,
) {
    fun ownerOf(
        viewerId: UUID?,
        owner: User,
        wishId: Long?,
    ): ExploreOwnerResult? {
        val exposure =
            ProfileExposure.of(
                viewerId = viewerId,
                ownerId = owner.getId(),
                isOwnerProfilePublic = owner.isProfilePublic,
                isBlocked = isBlockedEitherWay(viewerId, owner.getId()),
            )
        // 그 사람의 공개 폴더를 볼 수 있는 사람이 곧 그 사람을 담은 사람으로 볼 수 있는 사람이다
        if (!exposure.canSeeFolder(isFolderPublic = true)) return null
        val folder = wishId?.let { exploreWishJpaRepository.findPublicFoldersOf(it, owner.getId()).firstOrNull() }
        return ExploreOwnerResult(
            userId = owner.getId(),
            nickname = owner.nickname,
            profileImage = owner.profileImage,
            relation = relationOf(viewerId, owner.getId()),
            folder = folder?.let { ExploreFolderResult(folderId = it.getId(), name = it.name) },
        )
    }

    private fun isBlockedEitherWay(
        viewerId: UUID?,
        ownerId: UUID,
    ): Boolean {
        viewerId ?: return false
        return userBlockRepository.existsBetween(viewerId, ownerId)
    }

    private fun relationOf(
        viewerId: UUID?,
        ownerId: UUID,
    ): ProfileRelation {
        viewerId ?: return ProfileRelation.NOT_FRIEND
        if (viewerId == ownerId) return ProfileRelation.SELF
        if (friendshipRepository.exists(viewerId, ownerId)) return ProfileRelation.FRIEND
        return ProfileRelation.NOT_FRIEND
    }
}
