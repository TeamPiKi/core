package com.depromeet.piki.folder.controller.dto

import com.depromeet.piki.folder.domain.FolderException
import com.depromeet.piki.folder.domain.FolderName
import com.depromeet.piki.folder.domain.FolderWishIds
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema

// 이름·개수 규칙은 값 객체가 맡아 FOLDER code 로 거절한다. Bean Validation 을 두지 않는 이유는
// 같은 규칙이 COMMON-INVALID-INPUT 과 FOLDER code 로 갈려 나가지 않게 하기 위해서다
@Schema(description = "폴더 만들기·이름 바꾸기 요청")
data class FolderNameRequest(
    @field:Schema(
        description = "폴더 이름. 앞뒤 공백을 지운 뒤 1-20자. 이모지·결합 문자는 1자로 센다. 같은 이름의 폴더를 여러 개 만들 수 있다",
        example = "겨울 아우터",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val name: String?,
) {
    fun toFolderName(): FolderName = FolderName.of(name)
}

@Schema(description = "폴더에 위시 넣기 요청")
data class FolderWishesRequest(
    @field:ArraySchema(
        arraySchema =
            Schema(
                description = "넣을 위시 id. 1-100개, 중복은 하나로 센다. 이미 폴더에 든 위시는 건너뛴다",
                example = "[101, 102, 103]",
                requiredMode = Schema.RequiredMode.REQUIRED,
            ),
    )
    val wishIds: List<Long>?,
) {
    fun toFolderWishIds(): FolderWishIds = FolderWishIds.of(wishIds)
}

@Schema(description = "다른 폴더로 위시 옮기기 요청")
data class FolderMoveRequest(
    @field:ArraySchema(
        arraySchema =
            Schema(
                description = "옮길 위시 id. 1-100개, 중복은 하나로 센다. 이 폴더에 든 위시만 옮긴다",
                example = "[101, 102]",
                requiredMode = Schema.RequiredMode.REQUIRED,
            ),
    )
    val wishIds: List<Long>?,
    @field:Schema(description = "옮겨 갈 폴더 id. 이 폴더와 같으면 400", example = "15", requiredMode = Schema.RequiredMode.REQUIRED)
    val targetFolderId: Long?,
) {
    fun toFolderWishIds(): FolderWishIds = FolderWishIds.of(wishIds)

    // 대상이 빠진 요청은 "그런 폴더 없음" 과 같게 다룬다
    fun toTargetFolderId(): Long = targetFolderId ?: throw FolderException.notFound()
}
