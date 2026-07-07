package com.depromeet.piki.image.service

import com.depromeet.piki.common.storage.ImageStorage
import com.depromeet.piki.common.storage.S3Properties
import com.depromeet.piki.image.domain.ImageUploadException
import com.depromeet.piki.image.domain.PendingUpload
import com.depromeet.piki.image.domain.ProductImage
import com.depromeet.piki.image.repository.PendingUploadRepository
import com.depromeet.piki.image.service.dto.PresignedRawUpload
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

// 이미지 등록 v2 의 공통 presigned 업로드 프리미티브 — 위시·토너먼트가 권한 검증 후 위임한다.
//   발급: content-type 을 검증해 raw key(items/raw/{UUID}.{ext})를 만들고, 클라가 서버를 거치지 않고 S3 에 직접 PUT 할
//         presigned URL 을 준다. 발급된 key 는 pending_uploads 에 맥락과 함께 커밋해, confirm 이 안 와도 폴링 백스톱이
//         S3 존재를 확인해 등록할 수 있게 한다(클라 신호에 의존하지 않는 at-least-once).
//   확정 검증: 클라가 되돌려준 key 가 우리 발급 형식인지 + 실제로 S3 에 올라왔는지(HEAD) 확인한다.
//   raw 회수: item 에 매이지 못한 raw 를 best-effort 로 삭제한다(v1 경로가 거부·실패 시 사용).
// 개수 검증(1~5)은 도메인 계약이라 호출부(위시=member, 토너먼트=참여자·상태)가 각자 담당한다 — 여기선 형식·존재만 본다.
@Service
class ImagePresignService(
    private val imageStorage: ImageStorage,
    private val s3Properties: S3Properties,
    private val pendingUploadRepository: PendingUploadRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // presign 서명은 로컬 계산(네트워크 없음)이라 pending 커밋과 한 트랜잭션으로 묶어도 커넥션을 오래 잡지 않는다.
    // exists(HEAD, 외부 호출)는 여기 없다 — confirm/폴링이 트랜잭션 밖에서 먼저 확인한 뒤 등록(claim)을 부른다.
    // 발급된 key 를 어느 맥락(위시/토너먼트)의 pending 으로 적을지는 호출부가 pendingOf 로 정한다 — PendingUpload 의 팩토리가
    // 맥락 정합(WISH↔tournamentId 없음, TOURNAMENT↔필수)을 강제하므로, 맥락 인코딩이 PendingUpload 한 곳에만 산다.
    @Transactional
    fun presignRawUploads(
        contentTypes: List<String>,
        pendingOf: (imageKey: String, expiresAt: LocalDateTime) -> PendingUpload,
    ): List<PresignedRawUpload> {
        // 만료는 presigned 유효기간 + 여유 — 그 안에 업로드+등록이 끝나지 않으면 폴링이 이 매핑을 정리한다.
        val expiresAt = LocalDateTime.now().plus(s3Properties.presignedUploadExpiry).plus(PENDING_GRACE)
        val uploads =
            contentTypes.map { contentType ->
                // 미지정·미지원 content-type 은 발급 시점에 400 으로 거른다(ProductImage 가 of() 와 같은 검증을 공유).
                val extension = ProductImage.extensionForMimeType(contentType)
                val key = "$RAW_PREFIX${UUID.randomUUID()}.$extension"
                val url = imageStorage.presignUpload(key, contentType, s3Properties.presignedUploadExpiry)
                PresignedRawUpload(imageKey = key, uploadUrl = url, contentType = contentType)
            }
        pendingUploadRepository.saveAll(uploads.map { pendingOf(it.imageKey, expiresAt) })
        return uploads
    }

    fun verifyUploaded(imageKeys: List<String>) {
        imageKeys.forEach { key ->
            // 우리가 발급하는 raw key 형식이 아니면 클라가 임의 경로를 준 것 — 400.
            if (!RAW_KEY_REGEX.matches(key)) throw ImageUploadException.invalidKey()
            // presigned 로 실제 올리지 않고 confirm 을 부른 것 — 400 (스토리지 장애면 exists 가 502 로 던진다).
            if (!imageStorage.exists(key)) throw ImageUploadException.notUploaded()
        }
    }

    // item 에 매이지 못한 raw(거부·실패 등록의 orphan)를 best-effort 로 회수한다 — v1(multipart) 경로가 persist 거부·실패 시 부른다.
    // 삭제 실패가 원래 예외(클라이언트로 나갈 사유)를 덮지 않게 runCatching 으로 삼키고 경고만 남긴다. 회수 못 한 raw 는
    // items/raw/ S3 lifecycle 이 백업으로 만료한다.
    fun deleteRawsQuietly(imageKeys: List<String>) {
        imageKeys.forEach { key ->
            runCatching { imageStorage.delete(key) }
                .onFailure { e -> log.warn("raw {} 회수 실패(lifecycle 이 만료): {}", key, e.message) }
        }
    }

    companion object {
        const val RAW_PREFIX = "items/raw/"

        // pending 매핑 만료 여유 — presigned 유효기간이 지나 업로드가 불가능해진 뒤에도 마지막 폴링이 한 번 더
        // 등록을 시도할 짧은 유예. 이 시간까지 안 올라오면 폴링이 매핑을 정리한다.
        private val PENDING_GRACE: Duration = Duration.ofMinutes(2)

        // items/raw/{UUID}.{ext} — presignRawUploads 가 만드는 key 와 정확히 일치해야 한다. UUID.toString() 은 소문자 hex 라
        // [0-9a-f] 로 충분하고, 확장자 집합은 ProductImage.EXTENSIONS 에서 파생해 지원 포맷 추가 시 자동 추종한다(수동 동기화 제거).
        private val RAW_KEY_REGEX =
            Regex(
                "^${RAW_PREFIX}[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" +
                    "\\.(${ProductImage.EXTENSIONS.joinToString("|")})$",
            )
    }
}
