package com.depromeet.piki.image.controller.dto

import com.depromeet.piki.image.service.dto.PresignedRawUpload
import io.swagger.v3.oas.annotations.media.Schema

// presigned 발급 응답 — 각 이미지의 업로드 URL·key. 클라는 uploadUrl 로 S3 에 직접 PUT 한 뒤,
// imageKey 들을 confirm 요청으로 되돌려준다.
// 한 건짜리 표현은 PresignedImageUpload 가 갖고 여기선 목록으로만 감싼다(한 장 발급 경로가 그 타입을 직접 쓴다).
@Schema(description = "presigned 업로드 URL 발급 응답")
data class PresignedImageUploadResponse(
    @field:Schema(description = "발급된 업로드 대상 목록")
    val uploads: List<PresignedImageUpload>,
) {
    companion object {
        fun from(uploads: List<PresignedRawUpload>): PresignedImageUploadResponse =
            PresignedImageUploadResponse(uploads = uploads.map(PresignedImageUpload::from))
    }
}
