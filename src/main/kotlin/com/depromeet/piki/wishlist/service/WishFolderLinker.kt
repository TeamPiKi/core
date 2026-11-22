package com.depromeet.piki.wishlist.service

import com.depromeet.piki.wishlist.domain.WishFolderResult
import java.util.UUID

// 위시를 담으며 폴더에 넣는 연결. 폴더 도메인이 구현한다 — 위시 도메인은 폴더를 모르고, 의존은 폴더에서 위시 한 방향이다
interface WishFolderLinker {
    // 등록 전에 거른다. 없는 폴더·남의 폴더면 FOLDER-001
    fun requireOwned(
        userId: UUID,
        folderId: Long,
    )

    // 호출 트랜잭션 안에서 폴더 행을 잠그고 넣는다. 회원 행을 먼저 잠근 뒤 불러야 폴더 쓰기와 잠금 순서가 같다
    fun link(
        userId: UUID,
        folderId: Long,
        wishId: Long,
    ): WishFolderResult
}
