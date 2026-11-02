package com.depromeet.piki.folder.controller.dto

import com.depromeet.piki.folder.domain.FolderException
import com.depromeet.piki.folder.domain.FolderName
import com.depromeet.piki.folder.domain.FolderWishIds

// 이름·개수 규칙은 값 객체가 맡아 FOLDER code 로 거절한다. Bean Validation 을 두지 않는 이유는
// 같은 규칙이 COMMON-INVALID-INPUT 과 FOLDER code 로 갈려 나가지 않게 하기 위해서다
data class FolderNameRequest(
    val name: String?,
) {
    fun toFolderName(): FolderName = FolderName.of(name)
}

data class FolderWishesRequest(
    val wishIds: List<Long>?,
) {
    fun toFolderWishIds(): FolderWishIds = FolderWishIds.of(wishIds)
}

data class FolderMoveRequest(
    val wishIds: List<Long>?,
    val targetFolderId: Long?,
) {
    fun toFolderWishIds(): FolderWishIds = FolderWishIds.of(wishIds)

    // 대상이 빠진 요청은 "그런 폴더 없음" 과 같게 다룬다
    fun toTargetFolderId(): Long = targetFolderId ?: throw FolderException.notFound()
}
