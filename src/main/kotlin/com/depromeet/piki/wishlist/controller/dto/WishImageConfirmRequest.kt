package com.depromeet.piki.wishlist.controller.dto

import com.depromeet.piki.image.domain.ProductImageKey
import com.depromeet.piki.wishlist.service.dto.ConfirmWishImageRegistrationCommand
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

// 이미지 등록 확정 요청. 토너먼트와 함께 쓰던 ConfirmImageUploadRequest 에 폴더 필드를 얹으면 토너먼트 문서에도
// 쓰지 않는 필드가 보이므로 위시 전용으로 둔다. 개수·형식·존재 검증은 서버가 도메인 계약으로 해 Bean Validation 을 걸지 않는다.
@Schema(description = "위시 이미지 등록 확정 요청")
data class WishImageConfirmRequest(
    @field:Schema(
        description = "업로드를 마친 이미지 key 목록 (발급 응답의 imageKey, 1~5개)",
        example = "[\"items/raw/550e8400-e29b-41d4-a716-446655440000.png\"]",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val imageKeys: List<String>,
    @field:Schema(
        description = "담으면서 함께 넣을 내 폴더 id (폴더 상세에서 담을 때). 없으면 전체 위시에만 담는다",
        example = "12",
        nullable = true,
    )
    val folderId: Long? = null,
) {
    fun toCommand(userId: UUID): ConfirmWishImageRegistrationCommand =
        ConfirmWishImageRegistrationCommand(userId = userId, imageKeys = imageKeys.map(ProductImageKey::parse), folderId = folderId)
}
