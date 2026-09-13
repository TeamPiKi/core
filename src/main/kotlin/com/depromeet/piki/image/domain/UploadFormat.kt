package com.depromeet.piki.image.domain

@ConsistentCopyVisibility
data class UploadFormat private constructor(
    val contentType: String,
    val extension: String,
    val size: UploadSize,
) {
    companion object {
        fun of(
            contentType: String?,
            contentLength: Long?,
        ): UploadFormat {
            val extension = ProductImage.extensionForMimeType(contentType)
            // 선언값 그대로 서명한다 — 클라 PUT 헤더와 문자열이 같아야 한다.
            return UploadFormat(requireNotNull(contentType), extension, UploadSize.of(contentLength))
        }
    }
}
