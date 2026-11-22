package com.depromeet.piki.profile.domain

import java.util.UUID

// 보는 사람이 주인의 폴더·위시를 볼 수 있는지 판정한다(#1285 노출 규칙). 프로필·폴더 상세·탐색이 같은 판정을 쓴다.
// 닉네임·프로필 이미지는 늘 보이므로 여기서 다루지 않는다. 친구 여부는 버튼만 바꾸고 노출에는 관여하지 않는다
class ProfileExposure private constructor(
    private val isOwner: Boolean,
    private val isOpen: Boolean,
) {
    // 주인 본인은 비공개 폴더까지 전부 본다
    fun canSeeFolder(isFolderPublic: Boolean): Boolean = isOwner || (isOpen && isFolderPublic)

    // 프로필에서 위시는 폴더를 거쳐서만 보인다. 공개 폴더 하나에라도 들어 있으면 보인다
    fun canSeeWishOnProfile(isPublicByContainingFolder: Collection<Boolean>): Boolean =
        isOwner || (isOpen && isPublicByContainingFolder.any { it })

    // 탐색은 폴더에 없는 위시도 보인다. 폴더에 들어 있으면 공개 폴더 하나에라도 있어야 한다
    fun canSeeWishInExplore(isPublicByContainingFolder: Collection<Boolean>): Boolean =
        isOwner || (isOpen && (isPublicByContainingFolder.isEmpty() || isPublicByContainingFolder.any { it }))

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
