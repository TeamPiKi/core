package com.depromeet.piki.image.service

import com.depromeet.piki.common.storage.ImageStorage
import com.depromeet.piki.common.storage.S3Properties
import com.depromeet.piki.image.domain.ImageKey
import com.depromeet.piki.image.domain.ImageUploadException
import com.depromeet.piki.image.domain.UploadFormat
import com.depromeet.piki.image.domain.UploadSize
import com.depromeet.piki.image.service.dto.PresignedRawUpload
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

// 등록에 매이지 못한 raw 는 지우지 않는다. 등록된 raw 가 이미지 아이템의 정체 값이 되므로(#1180) lifecycle 만료도 걸지 않는다
@Service
class ImagePresignService(
    private val imageStorage: ImageStorage,
    private val s3Properties: S3Properties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun presignRawUploads(formats: List<UploadFormat>): List<PresignedRawUpload> =
        formats.map { presignRawUpload(it.extension, it.contentType, it.size) }

    fun presignRawUpload(
        extension: String,
        contentType: String,
        size: UploadSize,
    ): PresignedRawUpload {
        val key = ImageKey.newRaw(extension)
        val url = imageStorage.presignUpload(key.value, contentType, size.bytes, s3Properties.presignedUploadExpiry)
        return PresignedRawUpload(imageKey = key, uploadUrl = url, contentType = contentType)
    }

    fun verifyUploaded(keys: List<ImageKey>) {
        keys.forEach { key ->
            if (!imageStorage.exists(key.value)) throw ImageUploadException.notUploaded()
        }
    }
}
