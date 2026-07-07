package com.depromeet.piki.image.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

// 이미지 등록 v2 확정 요청 — presigned 로 업로드를 마친 이미지 key 목록(발급 응답의 imageKey, 1~5개).
// 개수·형식·존재 검증은 서버가 하므로 Bean Validation 을 걸지 않는다(v1 multipart 경로와 대칭).
@Schema(description = "이미지 등록 확정 요청")
data class ConfirmImageUploadRequest(
    @field:Schema(
        description = "업로드를 마친 이미지 key 목록 (발급 응답의 imageKey, 1~5개)",
        example = "[\"items/raw/550e8400-e29b-41d4-a716-446655440000.png\"]",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val imageKeys: List<String>,
)
