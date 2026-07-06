package com.depromeet.piki.image.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

// 이미지 등록 v2 presigned 발급 요청 — 올릴 이미지들의 content-type 목록(1~5개).
// 개수·형식 검증은 서버가 도메인 계약으로 하므로(v1 multipart 경로와 대칭) Bean Validation 을 걸지 않는다.
@Schema(description = "presigned 업로드 URL 발급 요청")
data class PresignedImageUploadRequest(
    @field:Schema(
        description = "업로드할 각 이미지의 content-type (1~5개, png/jpeg/webp/heic/heif 만 지원)",
        example = "[\"image/png\", \"image/jpeg\"]",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val contentTypes: List<String>,
)
