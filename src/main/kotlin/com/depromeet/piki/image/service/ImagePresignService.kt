package com.depromeet.piki.image.service

import com.depromeet.piki.common.storage.ImageStorage
import com.depromeet.piki.common.storage.S3Properties
import com.depromeet.piki.image.domain.ImageUploadException
import com.depromeet.piki.image.domain.ProductImage
import com.depromeet.piki.image.domain.UploadFormat
import com.depromeet.piki.image.service.dto.PresignedRawUpload
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

// 등록에 매이지 못한 raw 는 여기서 지우지 않는다 - items/raw/ S3 lifecycle 이 만료시킨다.
@Service
class ImagePresignService(
    private val imageStorage: ImageStorage,
    private val s3Properties: S3Properties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun presignRawUploads(formats: List<UploadFormat>): List<PresignedRawUpload> =
        formats.map { presignRawUpload(it.extension, it.contentType) }

    fun presignRawUpload(
        extension: String,
        contentType: String,
    ): PresignedRawUpload {
        val key = "$RAW_PREFIX${UUID.randomUUID()}.$extension"
        val url = imageStorage.presignUpload(key, contentType, s3Properties.presignedUploadExpiry)
        return PresignedRawUpload(imageKey = key, uploadUrl = url, contentType = contentType)
    }

    fun extensionOf(imageKey: String): String = imageKey.substringAfterLast('.')

    fun verifyUploaded(imageKeys: List<String>) {
        imageKeys.forEach { key ->
            if (!RAW_KEY_REGEX.matches(key)) throw ImageUploadException.invalidKey()
            if (!imageStorage.exists(key)) throw ImageUploadException.notUploaded()
        }
    }

    companion object {
        const val RAW_PREFIX = "items/raw/"

        private val RAW_KEY_REGEX =
            Regex(
                "^${RAW_PREFIX}[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" +
                    "\\.(${ProductImage.EXTENSIONS.joinToString("|")})$",
            )
    }
}
