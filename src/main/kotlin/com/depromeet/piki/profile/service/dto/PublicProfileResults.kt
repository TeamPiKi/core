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
    // 볼 수 없으면 빈 목록. "공개한 폴더가 없음" 과 같은 모양이다 — 가르면 비공개·차단이 드러난다
    val folders: List<PublicFolderSummary>,
)

data class PublicFolderSummary(
    val folderId: Long,
    val name: String,
    val wishCount: Int,
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
)
