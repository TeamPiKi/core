package com.depromeet.piki.user.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.image.controller.dto.PresignedImageUpload
import com.depromeet.piki.user.controller.dto.MyProfileResponse
import com.depromeet.piki.user.controller.dto.NicknameCheckRequest
import com.depromeet.piki.user.controller.dto.NicknameCheckResponse
import com.depromeet.piki.user.controller.dto.ProfileImagePresignRequest
import com.depromeet.piki.user.controller.dto.UserResponse
import com.depromeet.piki.user.controller.dto.UserUpdateRequest
import com.depromeet.piki.user.service.ProfileUpdateService
import com.depromeet.piki.user.service.UserService
import com.depromeet.piki.user.service.WithdrawalService
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/users")
class UserController(
    private val userService: UserService,
    private val withdrawalService: WithdrawalService,
    private val profileUpdateService: ProfileUpdateService,
) : UserApi {
    @GetMapping("/me")
    override fun getMe(
        @AuthenticationPrincipal userId: UUID,
    ): ApiResponseBody<MyProfileResponse> {
        val profile = userService.getMyProfile(userId)
        return ApiResponseBody.ok(MyProfileResponse.from(profile.user, profile.email))
    }

    @PostMapping("/me/profile-image")
    override fun presignProfileImage(
        @AuthenticationPrincipal userId: UUID,
        @Valid @RequestBody request: ProfileImagePresignRequest,
    ): ApiResponseBody<PresignedImageUpload> {
        // 업로드 URL 발급만 한다 — 바이트는 클라가 S3 로 직접 보내고 서버를 거치지 않는다.
        // 권한(MEMBER)·형식 검증은 발급 단계에서 끝내, 올릴 자격이 없는 요청에 URL 을 주지 않는다.
        val upload = profileUpdateService.presignProfileImage(userId, request.contentType)
        return ApiResponseBody.ok(PresignedImageUpload.from(upload))
    }

    @PatchMapping("/me")
    override fun updateMe(
        @AuthenticationPrincipal userId: UUID,
        @Valid @RequestBody request: UserUpdateRequest,
    ): ApiResponseBody<UserResponse> {
        // 닉네임·프로필 이미지를 한 요청으로 부분 수정한다 — 들어온 필드만 갱신. 이미지는 key 만 실려 오고,
        // 서버가 raw 를 읽어 검증한 뒤 확정 경로에 저장한다(ProfileUpdateService). 외부 호출은 트랜잭션 밖에서,
        // 영속화는 짧은 단일 트랜잭션으로 처리해 부분 성공을 막는다.
        // email 은 수정 대상이 아니므로 수정 응답엔 담지 않는다 (PII 표면 최소화). 마이페이지 email 은 GET /me 가 제공.
        val user = profileUpdateService.updateMe(userId, request.nickname, request.imageKey)
        return ApiResponseBody.ok(UserResponse.from(user))
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.OK)
    override fun withdraw(
        @AuthenticationPrincipal userId: UUID,
    ): ApiResponseBody<Unit> {
        withdrawalService.withdraw(userId)
        return ApiResponseBody.ok()
    }

    @GetMapping("/nickname/check")
    override fun checkNickname(
        @AuthenticationPrincipal userId: UUID?,
        @Valid request: NicknameCheckRequest,
    ): ApiResponseBody<NicknameCheckResponse> =
        ApiResponseBody.ok(
            NicknameCheckResponse(available = userService.isNicknameAvailable(request.nickname, userId)),
        )
}
