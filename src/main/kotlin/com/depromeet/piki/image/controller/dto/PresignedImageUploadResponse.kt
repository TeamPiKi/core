package com.depromeet.piki.image.controller.dto

import com.depromeet.piki.image.service.dto.PresignedRawUpload
import io.swagger.v3.oas.annotations.media.Schema

// presigned 발급 응답 — 각 이미지의 업로드 URL·key. 클라는 uploadUrl 로 S3 에 직접 PUT 한 뒤,
// imageKey 들을 confirm 요청으로 되돌려준다.
@Schema(description = "presigned 업로드 URL 발급 응답")
data class PresignedImageUploadResponse(
    @field:Schema(description = "발급된 업로드 대상 목록")
    val uploads: List<PresignedImageUpload>,
) {
    @Schema(description = "업로드 대상 한 건")
    data class PresignedImageUpload(
        @field:Schema(
            description = "confirm 때 되돌려줄 이미지 key",
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
    )

    companion object {
        fun from(uploads: List<PresignedRawUpload>): PresignedImageUploadResponse =
            PresignedImageUploadResponse(
                uploads =
                    uploads.map {
                        PresignedImageUpload(
                            imageKey = it.imageKey,
                            uploadUrl = it.uploadUrl,
                            contentType = it.contentType,
                        )
                    },
            )
    }
}
