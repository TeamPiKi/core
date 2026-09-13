package com.depromeet.piki.image.domain

@JvmInline
value class UploadSize private constructor(
    val bytes: Long,
) {
    companion object {
        const val MAX_BYTES: Long = 5L * 1024 * 1024

        fun of(contentLength: Long?): UploadSize {
            val bytes = contentLength ?: throw ImageUploadException.invalidSize()
            if (bytes <= 0) throw ImageUploadException.invalidSize()
            if (bytes > MAX_BYTES) throw ImageUploadException.tooLarge()
            return UploadSize(bytes)
        }
    }
}
