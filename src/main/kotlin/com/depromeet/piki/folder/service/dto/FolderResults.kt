package com.depromeet.piki.folder.service.dto

import com.depromeet.piki.folder.domain.Folder

data class FolderResult(
    val folder: Folder,
    val wishCount: Int,
    // 최근 넣은 위시 최대 3개의 이미지. 이미지가 없는 위시(파싱 중·실패)는 null
    val coverImageUrls: List<String?>,
)

data class FolderWishCountResult(
    val folderId: Long,
    val wishCount: Int,
)

data class FolderMoveResult(
    val source: FolderWishCountResult,
    val target: FolderWishCountResult,
)
