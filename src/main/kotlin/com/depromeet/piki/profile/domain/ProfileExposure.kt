package com.depromeet.piki.profile.domain

import java.util.UUID

// 보는 사람이 주인의 폴더를 볼 수 있는지 판정한다(#1285 노출 규칙). 프로필·폴더 상세·탐색의 위시 상세가 같은 판정을 쓴다.
// 탐색 피드의 위시는 폴더와 무관하게 프로필 공개 여부로만 노출된다(#1292). 친구 여부는 버튼만 바꾸고 노출에는 관여하지 않는다
class ProfileExposure private constructor(
    private val isOwner: Boolean,
    private val isOpen: Boolean,
) {
    // 주인 본인은 비공개 폴더까지 전부 본다
    fun canSeeFolder(isFolderPublic: Boolean): Boolean = isOwner || (isOpen && isFolderPublic)

    companion object {
        // viewerId 가 null 이면 비로그인. isBlocked 는 어느 쪽이 차단했든 true(4단계 차단이 채움)
        fun of(
            viewerId: UUID?,
            ownerId: UUID,
            isOwnerProfilePublic: Boolean,
            isBlocked: Boolean = false,
        ): ProfileExposure {
            if (viewerId == ownerId) return ProfileExposure(isOwner = true, isOpen = true)
            return ProfileExposure(isOwner = false, isOpen = isOwnerProfilePublic && !isBlocked)
        }
    }
}
