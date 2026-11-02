package com.depromeet.piki.folder.controller.dto

import com.depromeet.piki.folder.service.dto.FolderMoveResult
import com.depromeet.piki.folder.service.dto.FolderResult
import com.depromeet.piki.folder.service.dto.FolderWishCountResult
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "폴더")
data class FolderResponse(
    @field:Schema(description = "폴더 id", example = "12")
    val id: Long,
    @field:Schema(description = "폴더 이름", example = "겨울 아우터")
    val name: String,
    @field:Schema(description = "폴더 안 위시 수. 지운 위시는 세지 않는다", example = "7")
    val wishCount: Int,
    @field:ArraySchema(
        arraySchema =
            Schema(
                description =
                    "표지 이미지 URL. 최근 넣은 위시부터 이미지가 있는 것만 최대 3장. 파싱 중·실패 위시는 이미지가 없어 건너뛴다. " +
                        "폴더가 비었거나 이미지가 있는 위시가 없으면 빈 배열",
                example = "[\"https://cdn.example.com/p/701.jpg\", \"https://cdn.example.com/p/698.jpg\"]",
            ),
    )
    val coverImageUrls: List<String>,
    @field:Schema(description = "폴더를 만든 시각. 목록은 이 순서(먼저 만든 폴더가 앞)", example = "2026-10-20T13:02:11")
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

@Schema(description = "넣기·빼기 뒤 폴더의 위시 수")
data class FolderWishCountResponse(
    @field:Schema(description = "폴더 id", example = "12")
    val folderId: Long,
    @field:Schema(description = "넣기·빼기를 반영한 뒤 폴더 안 위시 수. 지운 위시는 세지 않는다", example = "10")
    val wishCount: Int,
) {
    companion object {
        fun from(result: FolderWishCountResult): FolderWishCountResponse = FolderWishCountResponse(folderId = result.folderId, wishCount = result.wishCount)
    }
}

@Schema(description = "옮기기 뒤 두 폴더의 위시 수")
data class FolderMoveResponse(
    @field:Schema(description = "원래 폴더 id", example = "12")
    val folderId: Long,
    @field:Schema(description = "옮긴 뒤 원래 폴더의 위시 수", example = "6")
    val wishCount: Int,
    @field:Schema(description = "옮겨 간 폴더 id", example = "15")
    val targetFolderId: Long,
    @field:Schema(description = "옮긴 뒤 대상 폴더의 위시 수", example = "3")
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
