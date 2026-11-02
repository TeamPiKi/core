package com.depromeet.piki.folder.service.dto

import com.depromeet.piki.folder.domain.Folder

data class FolderResult(
    val folder: Folder,
    val wishCount: Int,
    val coverImageUrls: List<String>,
)

data class FolderWishCountResult(
    val folderId: Long,
    val wishCount: Int,
)

data class FolderMoveResult(
    val source: FolderWishCountResult,
    val target: FolderWishCountResult,
)
