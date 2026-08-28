package com.depromeet.piki.user.controller.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

// 프로필 이미지 presigned 발급 요청 — 올릴 이미지의 content-type 하나.
// 허용 형식 검증은 서버가 도메인 정책(ProfileImageFile)으로 하므로 여기선 공백만 막는다.
// 비어 있으면 발급 단계에서 걸러야 하는데, 빈 문자열은 "형식 미지원" 이 아니라 "요청이 덜 채워짐" 이라 400 이 맞다.
@Schema(description = "프로필 이미지 업로드 URL 발급 요청")
data class ProfileImagePresignRequest(
    @field:NotBlank(message = CONTENT_TYPE_REQUIRED_MESSAGE)
    @field:Schema(
        description = "올릴 이미지의 MIME 타입 (png · jpeg · webp · heic · heif)",
        example = "image/png",
    )
    val contentType: String,
) {
    // Bean Validation 위반 메시지의 single source (UserUpdateRequest 와 같은 규약).
    companion object {
        const val CONTENT_TYPE_REQUIRED_MESSAGE = "이미지 형식을 입력해 주세요."
    }
}
