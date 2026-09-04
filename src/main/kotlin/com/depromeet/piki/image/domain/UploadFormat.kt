package com.depromeet.piki.image.domain

data class UploadFormat private constructor(
    val contentType: String,
    val extension: String,
) {
    companion object {
        fun of(contentType: String): UploadFormat = UploadFormat(contentType, ProductImage.extensionForMimeType(contentType))
    }
}
