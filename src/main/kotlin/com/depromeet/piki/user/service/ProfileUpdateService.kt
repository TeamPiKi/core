package com.depromeet.piki.user.service

import com.depromeet.piki.common.storage.ImageStorage
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.ProfileImageFile
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.domain.UserException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

// 내 정보 수정(PATCH /me) 오케스트레이션. 이미지가 있으면 형식 검증 후 S3 업로드(외부 호출)를 트랜잭션 밖에서 끝내고,
// nickname + 이미지 URL 영속화는 UserService.updateProfile(@Transactional) 의 짧은 트랜잭션에 위임한다
// (## 트랜잭션 경계 — 외부 호출은 트랜잭션 밖, self-invocation 회피를 위해 별도 빈으로 분리).
@Service
class ProfileUpdateService(
    private val userService: UserService,
    private val imageStorage: ImageStorage,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun updateMe(
        userId: UUID,
        nickname: String?,
        image: MultipartFile?,
    ): User {
        // 이미지가 없으면 외부 호출(S3)이 없으므로 곧장 영속화로 — 닉네임만 갱신하거나(게스트도 가능), 둘 다 비면 무동작으로 통과한다.
        image ?: return userService.updateProfile(userId, nickname, null)
        // 프로필 이미지 수정은 MEMBER 전용. 권한을 형식 검증·업로드보다 먼저 본다 — 게스트의 이미지 파트는
        // 내용과 무관하게 403 으로 끊고(authorization before payload processing), orphan S3 업로드도 함께 막는다.
        val user = userService.findActiveById(userId)
        if (user.identityType != IdentityType.MEMBER) throw UserException.guestCannotUpdateProfileImage()
        // 형식 검증(빈 바이트·미지원 MIME·내용 불일치)을 업로드 전에 끝낸다 — 실패 시 즉시 400.
        val profileImage = ProfileImageFile.of(image.bytes, image.contentType)
        val key = "profiles/$userId/${UUID.randomUUID()}.${profileImage.extension}"
        val url = imageStorage.upload(profileImage.bytes, key, profileImage.mimeType) // 트랜잭션 밖, 실패 시 502
        log.info("프로필 이미지 업로드 완료: userId={}, key={}", userId, key)
        // 영속화가 떨어지면 방금 올린 객체가 아무도 안 가리키는 orphan 으로 남는다 — 닉네임 중복(409)이 흔한
        // 트리거이고, 활성 확인과 영속화 사이에 탈퇴가 커밋되면 탈퇴 cascade 의 prefix 파기가 이미 지나간 뒤라
        // 프로필 사진(얼굴 등 PII)이 S3 에 계속 남는다. 그래서 lifecycle 에 맡기지 않고 즉시 회수한다
        // (registerFromImages 의 raw 회수와 같은 패턴).
        return runCatching { userService.updateProfile(userId, nickname, url) }
            .onFailure { deleteQuietly(key) }
            .getOrThrow()
    }

    // 보상 삭제는 best-effort — 회수 자체가 실패해도 원래 예외(409 등)를 가리지 않도록 삼키고 로그만 남긴다.
    // delete 는 객체가 없어도 no-op(멱등)이라 언제 불러도 안전하다.
    private fun deleteQuietly(key: String) {
        runCatching { imageStorage.delete(key) }
            .onFailure { e -> log.warn("프로필 이미지 {} 회수 실패(orphan 잔존, lifecycle 대상): {}", key, e.message) }
    }
}
