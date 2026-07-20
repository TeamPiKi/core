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
        return userService.updateProfile(userId, nickname, url)
    }
}
