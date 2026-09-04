package com.depromeet.piki.image.domain

// 지원 형식임이 확인된 업로드 형식. of() 를 통과한 인스턴스만 존재하므로 뒤에서 다시 검증하지 않는다.
data class UploadFormat private constructor(
    val contentType: String,
    val extension: String,
) {
    companion object {
        fun of(contentType: String): UploadFormat = UploadFormat(contentType, ProductImage.extensionForMimeType(contentType))
    }
}
