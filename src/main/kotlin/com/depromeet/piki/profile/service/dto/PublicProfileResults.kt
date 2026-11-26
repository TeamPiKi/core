package com.depromeet.piki.profile.service.dto

import com.depromeet.piki.profile.domain.ProfileRelation
import com.depromeet.piki.wishlist.service.dto.WishResult
import java.util.UUID

// 다른 사람 프로필 한 화면. 닉네임·프로필 이미지는 항상 있고, 폴더만 노출 규칙을 탄다
data class PublicProfile(
    val userId: UUID,
    val nickname: String,
    val profileImage: String,
    val relation: ProfileRelation,
    // 보는 사람이 폴더를 볼 수 없으면 true. 공개 폴더가 0개인 것과 가르는 값이다(#1302).
    // 프로필 비공개와 상대가 나를 차단한 경우가 같은 true 라 차단이 드러나지 않는다
    val foldersHidden: Boolean,
    // 볼 수 없으면 빈 목록. "공개한 폴더가 없음" 과 같은 모양이다 — 가르면 비공개·차단이 드러난다
    val folders: List<PublicFolderSummary>,
)

data class PublicFolderSummary(
    val folderId: Long,
    val name: String,
    val wishCount: Int,
    // 최근 넣은 위시 최대 3개의 이미지. 이미지가 없는 위시는 null
    val coverImageUrls: List<String?>,
)

// 공개 폴더 하나와 그 안의 위시.
//
// 위시는 내 목록과 같은 WishResult 를 쓴다 — 화면값 계산을 한 곳에 두기 위해서다.
// 다만 folderIds 와 winnerTournamentIds 는 비워 둔다. 전자는 주인의 비공개 폴더 id 를 드러내고,
// 후자는 내 위시 화면의 칩이라 남의 폴더에서 보여줄 값이 아니다.
data class PublicFolderDetail(
    val folderId: Long,
    val name: String,
    val wishes: List<WishResult>,
    // 보는 사람이 이미 자기 위시로 가진 상품. 비로그인이면 비어 있다
    val savedItemIds: Set<Long> = emptySet(),
) {
    fun isSaved(wish: WishResult): Boolean = wish.wish.itemId?.let { it in savedItemIds } ?: false
}
