package com.depromeet.piki.user.service

import com.depromeet.piki.common.storage.ImageStorage
import com.depromeet.piki.image.service.ImagePresignService
import com.depromeet.piki.image.service.dto.PresignedRawUpload
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.ProfileImageFile
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.domain.UserException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

// 내 정보 수정(PATCH /me) 오케스트레이션. 이미지는 클라가 presigned URL 로 S3 에 직접 올리고(서버를 안 거친다),
// 확정 요청은 그 raw key 만 싣는다. 서버는 raw 를 읽어 형식을 검증한 뒤 최종 경로에 저장한다.
//
// 서버가 바이트를 읽는 이유: ProfileImageFile 의 매직바이트 교차검증(선언 MIME 과 실제 내용 일치)은 바이트를
// 봐야만 가능하다. 그럼에도 multipart 보다 나은 건, 느린 회선의 업로드를 우리 톰캣 스레드가 아니라
// 클라 <-> S3 구간이 감당하고 우리는 리전 내에서 짧게 한 번 읽기 때문이다.
//
// 최종 경로를 서버가 정하는 이유: 확정본은 profiles/{userId}/ 아래여야 탈퇴 cascade 의 prefix 파기가 닿는다
// (WithdrawalService). raw 에 그대로 두면 탈퇴 후에도 얼굴 사진이 남는다.
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
    ): PresignedRawUpload {
        requireMemberForProfileImage(userId)
        return imagePresignService.presignRawUpload(
            extension = ProfileImageFile.extensionForMimeType(contentType),
            contentType = contentType,
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
        // 우리가 발급한 key 형식인지 + 실제로 올라왔는지. 아니면 400/502 로 여기서 끝난다.
        imagePresignService.verifyUploaded(listOf(imageKey))
        // 발급 때 우리가 붙인 확장자로 "클라가 선언했던" MIME 을 되찾아, 실제 바이트와 일치하는지 교차검증한다.
        val declaredMimeType = ProfileImageFile.mimeTypeForExtension(imagePresignService.extensionOf(imageKey))
        val profileImage = ProfileImageFile.of(imageStorage.download(imageKey), declaredMimeType)

        val key = "$PROFILE_PREFIX$userId/${UUID.randomUUID()}.${profileImage.extension}"
        // 업로드부터 영속화까지를 한 묶음으로 보고, 어느 단계에서 떨어지든 이 key 를 회수한다. 영속화가 떨어지면
        // 방금 올린 객체가 아무도 안 가리키는 orphan 으로 남고(닉네임 중복 409 가 흔한 트리거), 활성 확인과 영속화
        // 사이에 탈퇴가 커밋되면 탈퇴 cascade 의 prefix 파기가 이미 지나간 뒤라 프로필 사진(얼굴 등 PII)이 계속 남는다.
        // upload 가 던진 경우도 포함한다 — 응답 유실·timeout 이면 S3 에는 객체가 올라갔을 수 있다. key 는 우리가
        // 만든 값이라 업로드 성공 여부와 무관하게 삭제를 걸 수 있고, 객체가 없으면 no-op 이라 안전하다.
        return runCatching {
            val url = imageStorage.upload(profileImage.bytes, key, profileImage.mimeType) // 트랜잭션 밖, 실패 시 502
            log.info("프로필 이미지 업로드 완료: userId={}, key={}", userId, key)
            userService.updateProfile(userId, nickname, url)
        }.onFailure { deleteQuietly(key) }
            .getOrThrow()
            // 확정본이 자리를 잡은 뒤에만 raw 를 회수한다. 실패해도 items/raw/ lifecycle(1일)이 만료하므로 best-effort.
            .also { deleteQuietly(imageKey) }
    }

    // 프로필 이미지 변경은 MEMBER 전용 — 게스트는 PII 를 갖지 않는다.
    private fun requireMemberForProfileImage(userId: UUID) {
        val user = userService.findActiveById(userId)
        if (user.identityType != IdentityType.MEMBER) throw UserException.guestCannotUpdateProfileImage()
    }

    // 보상 삭제는 best-effort — 회수 자체가 실패해도 원래 예외(409 등)를 가리지 않도록 삼키고 로그만 남긴다.
    // delete 는 객체가 없어도 no-op(멱등)이라 언제 불러도 안전하다.
    private fun deleteQuietly(key: String) {
        runCatching { imageStorage.delete(key) }
            .onFailure { e -> log.warn("프로필 이미지 {} 회수 실패(orphan 잔존, lifecycle 대상): {}", key, e.message) }
    }

    companion object {
        // 탈퇴 cascade 가 통째로 파기하는 prefix(WithdrawalService 의 deleteByPrefix 와 같은 값이어야 한다).
        const val PROFILE_PREFIX = "profiles/"
    }
}
