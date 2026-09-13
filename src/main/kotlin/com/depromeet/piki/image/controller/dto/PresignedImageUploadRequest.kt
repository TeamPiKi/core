package com.depromeet.piki.image.controller.dto

import com.depromeet.piki.image.domain.UploadFormat
import io.swagger.v3.oas.annotations.media.Schema

// 개수·형식·크기 검증은 서버가 도메인 계약으로 하므로 Bean Validation 을 걸지 않는다.
@Schema(description = "presigned 업로드 URL 발급 요청")
data class PresignedImageUploadRequest(
    @field:Schema(
        description = "업로드할 이미지 목록 (1~5개)",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val images: List<Image>,
) {
    @Schema(description = "업로드할 이미지 한 장의 content-type 과 바이트 수")
    data class Image(
        @field:Schema(
            description = "이미지의 content-type (png/jpeg/webp/heic/heif 만 지원). PUT 시 Content-Type 헤더와 같아야 한다.",
            example = "image/png",
            requiredMode = Schema.RequiredMode.REQUIRED,
        )
        val contentType: String?,
        @field:Schema(
            description = "이미지 파일의 바이트 수 (1 이상 5MB 이하). 서명에 묶여 PUT 시 Content-Length 와 같아야 한다.",
            example = "1048576",
            requiredMode = Schema.RequiredMode.REQUIRED,
        )
        val contentLength: Long?,
    )

    fun toUploadFormats(): List<UploadFormat> = images.map { UploadFormat.of(it.contentType, it.contentLength) }
}
