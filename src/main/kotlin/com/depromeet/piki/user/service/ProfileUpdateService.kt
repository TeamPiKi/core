package com.depromeet.piki.user.service

import com.depromeet.piki.common.storage.ImageStorage
import com.depromeet.piki.image.domain.ProfileImageKey
import com.depromeet.piki.image.domain.UploadSize
import com.depromeet.piki.image.service.ImagePresignService
import com.depromeet.piki.image.service.dto.PresignedRawUpload
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.ProfileImageFile
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.domain.UserException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

// 내 정보 수정(PATCH /me) 오케스트레이션. 이미지는 클라가 presigned URL 로 S3 의 profiles/{userId}/ 에 직접 올리고,
// 확정 요청은 그 key 만 싣는다. 서버는 그 객체를 읽어 형식을 검증한 뒤 그 URL 을 프로필로 삼는다.
//
// 서버가 바이트를 읽는 이유: ProfileImageFile 의 매직바이트 교차검증(선언 MIME 과 실제 내용 일치)은 바이트를
// 봐야만 가능하다. 그럼에도 multipart 보다 나은 건, 느린 회선의 업로드를 우리 톰캣 스레드가 아니라
// 클라 <-> S3 구간이 감당하고 우리는 리전 내에서 짧게 한 번 읽기 때문이다.
//
// 영속화는 UserService.updateProfile(@Transactional) 의 짧은 트랜잭션에 위임한다
// (## 트랜잭션 경계 — 외부 호출은 트랜잭션 밖, self-invocation 회피를 위해 별도 빈으로 분리).
@Service
class ProfileUpdateService(
    private val userService: UserService,
    private val imageStorage: ImageStorage,
    private val imagePresignService: ImagePresignService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 업로드 URL 발급. 권한(MEMBER)을 여기서 먼저 본다 — 게스트에게는 발급 자체를 막아 S3 에 올릴 기회를 주지 않는다.
    // 형식은 ProfileImageFile 의 허용 목록으로 거른다(확정 단계 of() 와 같은 정책).
    fun presignProfileImage(
        userId: UUID,
        contentType: String,
        contentLength: Long?,
    ): PresignedRawUpload {
        requireMemberForProfileImage(userId)
        return imagePresignService.presignProfileUpload(
            owner = userId,
            extension = ProfileImageFile.extensionForMimeType(contentType),
            contentType = contentType,
            size = UploadSize.of(contentLength),
        )
    }

    fun updateMe(
        userId: UUID,
        nickname: String?,
        imageKey: String?,
    ): User {
        // 이미지가 없으면 외부 호출이 없으므로 곧장 영속화로 — 닉네임만 갱신하거나(게스트도 가능), 둘 다 비면 무동작으로 통과한다.
        imageKey ?: return userService.updateProfile(userId, nickname, null)
        // 권한을 내려받기·검증보다 먼저 본다 — 게스트의 요청은 내용과 무관하게 403 으로 끊는다
        // (authorization before payload processing). 발급 단계에서 이미 막지만, 확정도 스스로 방어한다.
        requireMemberForProfileImage(userId)
        val key = ProfileImageKey.parse(imageKey, owner = userId)
        imagePresignService.verifyUploaded(listOf(key))
        val bytes = imageStorage.download(key.value)
        // 발급 때 우리가 붙인 확장자로 클라가 선언했던 MIME 을 되찾아 실제 바이트와 맞춘다. 어긋나면 이미지가 아닌 파일이 공개 버킷에 남지 않게 지운다
        runCatching { ProfileImageFile.of(bytes, ProfileImageFile.mimeTypeForExtension(key.extension)) }
            .onFailure { deleteQuietly(key.value) }
            .getOrThrow()
        // 저장이 실패해도 객체는 남긴다. 같은 키로 다시 확정할 수 있어야 하고 탈퇴 때 접두째 지워진다
        return userService.updateProfile(userId, nickname, imageStorage.urlOf(key.value))
    }

    // 프로필 이미지 변경은 MEMBER 전용 — 게스트는 PII 를 갖지 않는다.
    private fun requireMemberForProfileImage(userId: UUID) {
        val user = userService.findActiveById(userId)
        if (user.identityType != IdentityType.MEMBER) throw UserException.guestCannotUpdateProfileImage()
    }

    // 회수 실패가 원래 예외(400)를 가리지 않게 삼킨다
    private fun deleteQuietly(key: String) {
        runCatching { imageStorage.delete(key) }
            .onFailure { e -> log.warn("프로필 이미지 {} 회수 실패(탈퇴 때 접두째 지워짐): {}", key, e.message) }
    }
}
