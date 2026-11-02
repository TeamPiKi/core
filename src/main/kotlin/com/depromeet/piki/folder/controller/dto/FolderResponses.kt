package com.depromeet.piki.folder.controller.dto

import com.depromeet.piki.folder.service.dto.FolderMoveResult
import com.depromeet.piki.folder.service.dto.FolderResult
import com.depromeet.piki.folder.service.dto.FolderWishCountResult
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class FolderResponse(
    val id: Long,
    val name: String,
    @field:Schema(description = "폴더 안 위시 수. 지운 위시는 세지 않는다")
    val wishCount: Int,
    @field:Schema(description = "표지 이미지. 최근 넣은 위시부터 이미지가 있는 것만 최대 3장")
    val coverImageUrls: List<String>,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(result: FolderResult): FolderResponse =
            FolderResponse(
                id = result.folder.getId(),
                name = result.folder.name,
                wishCount = result.wishCount,
                coverImageUrls = result.coverImageUrls,
                createdAt = result.folder.createdAt,
            )
    }
}

data class FolderWishCountResponse(
    val folderId: Long,
    val wishCount: Int,
) {
    companion object {
        fun from(result: FolderWishCountResult): FolderWishCountResponse = FolderWishCountResponse(folderId = result.folderId, wishCount = result.wishCount)
    }
}

data class FolderMoveResponse(
    val folderId: Long,
    val wishCount: Int,
    val targetFolderId: Long,
    val targetWishCount: Int,
) {
    companion object {
        fun from(result: FolderMoveResult): FolderMoveResponse =
            FolderMoveResponse(
                folderId = result.source.folderId,
                wishCount = result.source.wishCount,
                targetFolderId = result.target.folderId,
                targetWishCount = result.target.wishCount,
            )
    }
}
