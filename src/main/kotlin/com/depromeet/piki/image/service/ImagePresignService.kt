package com.depromeet.piki.image.service

import com.depromeet.piki.common.storage.ImageStorage
import com.depromeet.piki.common.storage.S3Properties
import com.depromeet.piki.image.domain.ImageUploadException
import com.depromeet.piki.image.domain.PendingUpload
import com.depromeet.piki.image.domain.ProductImage
import com.depromeet.piki.image.domain.UploadFormat
import com.depromeet.piki.image.repository.PendingUploadRepository
import com.depromeet.piki.image.service.dto.PresignedRawUpload
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

// 등록에 매이지 못한 raw 는 여기서 지우지 않는다 - items/raw/ S3 lifecycle 이 만료시킨다.
@Service
class ImagePresignService(
    private val imageStorage: ImageStorage,
    private val s3Properties: S3Properties,
    private val pendingUploadRepository: PendingUploadRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // presign 서명은 네트워크를 타지 않는 로컬 계산이라 pending 커밋과 한 트랜잭션으로 묶어도 커넥션을 오래 잡지 않는다.
    @Transactional
    fun presignRawUploads(
        formats: List<UploadFormat>,
        pendingOf: (imageKey: String, expiresAt: LocalDateTime) -> PendingUpload,
    ): List<PresignedRawUpload> {
        val expiresAt = LocalDateTime.now().plus(s3Properties.presignedUploadExpiry).plus(PENDING_GRACE)
        val uploads =
            formats.map { format ->
                val key = "$RAW_PREFIX${UUID.randomUUID()}.${format.extension}"
                val url = imageStorage.presignUpload(key, format.contentType, s3Properties.presignedUploadExpiry)
                PresignedRawUpload(imageKey = key, uploadUrl = url, contentType = format.contentType)
            }
        pendingUploadRepository.saveAll(uploads.map { pendingOf(it.imageKey, expiresAt) })
        return uploads
    }

    // pending 을 남기지 않는 발급. 확정이 유실돼도 사용자가 다시 시도하면 그만인 경로가 쓴다.
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

        private val PENDING_GRACE: Duration = Duration.ofMinutes(2)

        private val RAW_KEY_REGEX =
            Regex(
                "^${RAW_PREFIX}[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" +
                    "\\.(${ProductImage.EXTENSIONS.joinToString("|")})$",
            )
    }
}
