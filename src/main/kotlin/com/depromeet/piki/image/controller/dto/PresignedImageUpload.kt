package com.depromeet.piki.image.controller.dto

import com.depromeet.piki.image.service.dto.PresignedRawUpload
import io.swagger.v3.oas.annotations.media.Schema

// presigned 업로드 대상 한 건. 여러 장을 한 번에 발급하는 경로(위시·토너먼트 등록)는 이 타입의 목록을
// PresignedImageUploadResponse 로 감싸고, 한 장뿐인 경로(프로필 이미지)는 이 타입을 그대로 응답한다 —
// 한 장짜리를 목록으로 감싸면 클라가 항상 [0] 을 꺼내야 해서, 모양만 맞추려고 계약을 어색하게 만들지 않는다.
@Schema(description = "presigned 업로드 대상 한 건")
data class PresignedImageUpload(
    @field:Schema(
        description = "업로드를 마친 뒤 서버에 되돌려줄 이미지 key",
        example = "items/raw/550e8400-e29b-41d4-a716-446655440000.png",
    )
    val imageKey: String,
    @field:Schema(
        description = "클라가 이미지를 직접 PUT 할 presigned URL (만료 5분)",
        example = "https://piki-images.s3.ap-northeast-2.amazonaws.com/items/raw/550e8400-e29b-41d4-a716-446655440000.png?X-Amz-Signature=...",
    )
    val uploadUrl: String,
    @field:Schema(
        description = "PUT 시 사용할 Content-Type 헤더 (presigned 서명에 포함되어 이 값으로만 업로드 가능)",
        example = "image/png",
    )
    val contentType: String,
) {
    companion object {
        fun from(upload: PresignedRawUpload): PresignedImageUpload =
            PresignedImageUpload(
                imageKey = upload.imageKey,
                uploadUrl = upload.uploadUrl,
                contentType = upload.contentType,
            )
    }
}
