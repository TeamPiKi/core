package com.depromeet.piki.user.controller

import com.depromeet.piki.common.exception.CommonErrorCode
import com.depromeet.piki.common.openapi.OpenApiObjectMapper
import com.depromeet.piki.common.openapi.binds
import com.depromeet.piki.common.openapi.examples
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.common.storage.ImageStorageException
import com.depromeet.piki.image.controller.dto.PresignedImageUpload
import com.depromeet.piki.image.domain.ImageUploadException
import com.depromeet.piki.user.controller.dto.MyProfileResponse
import com.depromeet.piki.user.controller.dto.NicknameCheckRequest
import com.depromeet.piki.user.controller.dto.NicknameCheckResponse
import com.depromeet.piki.user.controller.dto.ProfileImagePresignRequest
import com.depromeet.piki.user.controller.dto.UserResponse
import com.depromeet.piki.user.controller.dto.UserUpdateRequest
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.UserException
import java.util.UUID
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus

@Configuration
class UserApiExamples(
    private val openApiObjectMapper: OpenApiObjectMapper,
) {
    @Bean
    fun userOpenApiExamples(): OperationCustomizer =
        OperationCustomizer { operation, handlerMethod ->
            when {
                handlerMethod.binds(UserController::getMe) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "내 정보 조회 성공 (email 있음)",
                            payload = ApiResponseBody.ok(sampleMyProfile()),
                        )
                        add(
                            status = HttpStatus.OK,
                            name = "내 정보 조회 성공 (email 미수집·게스트)",
                            payload =
                                ApiResponseBody.ok(
                                    sampleMyProfile().copy(identityType = IdentityType.GUEST, email = null),
                                ),
                        )
                        unauthorized()
                        // 실제 응답은 UserException.notFound() → USER-001 이므로 예외에서 직접 example 을 만들어 code·detail 을 실제와 일치시킨다.
                        add(UserException.notFound(), name = "유저 없음 (JWT 유효하나 DB에 없음)")
                        add(UserException.deletedUser(), name = "탈퇴한 유저")
                    }

                handlerMethod.binds(UserController::presignProfileImage) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "발급 성공",
                            payload =
                                ApiResponseBody.ok(
                                    PresignedImageUpload(
                                        imageKey = SAMPLE_IMAGE_KEY,
                                        uploadUrl = "https://piki-images.s3.ap-northeast-2.amazonaws.com/$SAMPLE_IMAGE_KEY?X-Amz-Signature=...",
                                        contentType = "image/png",
                                    ),
                                ),
                        )
                        add(
                            status = HttpStatus.BAD_REQUEST,
                            name = "contentType 누락",
                            payload =
                                ApiResponseBody.fail<Unit>(
                                    CommonErrorCode.INVALID_INPUT,
                                    detail = ProfileImagePresignRequest.CONTENT_TYPE_REQUIRED_MESSAGE,
                                ),
                        )
                        add(UserException.unsupportedProfileImageType(), name = "지원하지 않는 이미지 형식")
                        unauthorized()
                        add(UserException.guestCannotUpdateProfileImage(), name = "게스트의 프로필 이미지 업로드 거부")
                        add(UserException.notFound(), name = "유저 없음 (JWT 유효하나 DB에 없음)")
                        add(UserException.deletedUser(), name = "탈퇴한 유저")
                        add(ImageStorageException.presignFailed(), name = "업로드 URL 발급 실패")
                    }

                handlerMethod.binds(UserController::updateMe) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "수정 성공 (닉네임·프로필 이미지)",
                            payload =
                                ApiResponseBody.ok(
                                    // 이미지 수정 성공 예시라 MEMBER 로 둔다 — sampleUser() 기본값(GUEST)을 그대로 쓰면
                                    // "게스트가 프로필 이미지를 수정 성공" 으로 읽혀, 같은 블록의 게스트 이미지 403 계약과 충돌한다.
                                    sampleUser().copy(
                                        nickname = "새닉네임",
                                        profileImage = "https://cdn.example.com/profiles/8f1a3c2b/9d44.jpg",
                                        identityType = IdentityType.MEMBER,
                                    ),
                                ),
                        )
                        add(
                            status = HttpStatus.BAD_REQUEST,
                            name = "닉네임 길이/공백 검증 실패",
                            payload =
                                ApiResponseBody.fail<Unit>(
                                    CommonErrorCode.INVALID_INPUT,
                                    // Bean Validation 위반은 GlobalExceptionHandler.detailOf 가 위반 필드의 메시지를 그대로 detail 로 내린다.
                                    detail = UserUpdateRequest.NICKNAME_SIZE_MESSAGE,
                                ),
                        )
                        // 우리가 발급하지 않은 key 형식 · 아직 S3 에 안 올라온 key — 확정 전에 걸러진다.
                        add(ImageUploadException.invalidKey(), name = "발급하지 않은 이미지 key")
                        add(ImageUploadException.notUploaded(), name = "업로드되지 않은 이미지 key")
                        // 원본을 읽어 검증한 결과 — 빈 파일, 발급 때 선언한 형식과 실제 내용 불일치(위조·손상).
                        add(UserException.emptyProfileImage(), name = "빈 이미지 파일")
                        add(UserException.malformedProfileImage(), name = "형식과 내용 불일치")
                        // @Size(min = 1) 은 공백 1자를 통과시키고, 예약 prefix 도 못 거른다 —
                        // 둘 다 User.validateNickname 이 잡아 400 으로 나간다.
                        add(UserException.nicknameBlank(), name = "공백만 있는 닉네임")
                        add(UserException.nicknameReserved(), name = "'탈퇴' 예약 prefix 로 시작하는 닉네임")
                        add(UserException.duplicateNickname(), name = "닉네임 중복")
                        add(UserException.deletedUser(), name = "탈퇴한 유저")
                        unauthorized()
                        add(UserException.guestCannotUpdateProfileImage(), name = "게스트의 프로필 이미지 수정 거부")
                        // 실제 응답은 UserException.notFound() → USER-001 이므로 예외에서 직접 example 을 만들어 code·detail 을 실제와 일치시킨다.
                        add(UserException.notFound(), name = "유저 없음 (JWT 유효하나 DB에 없음)")
                        add(ImageStorageException.existsCheckFailed(), name = "이미지 저장소(S3) 업로드 상태 확인 실패")
                        add(ImageStorageException.downloadFailed(), name = "이미지 저장소(S3) 원본 읽기 실패")
                        add(ImageStorageException.uploadFailed(), name = "이미지 저장소(S3) 저장 실패")
                    }

                handlerMethod.binds(UserController::withdraw) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "탈퇴 성공",
                            payload = ApiResponseBody.ok<Unit>(),
                        )
                        unauthorized()
                        add(UserException.guestCannotWithdraw(), name = "게스트 탈퇴 거부")
                        // 실제 응답은 UserException.notFound() → USER-001 이므로 예외에서 직접 example 을 만들어 code·detail 을 실제와 일치시킨다.
                        add(UserException.notFound(), name = "유저 없음 (JWT 유효하나 DB에 없음)")
                    }

                handlerMethod.binds(UserController::checkNickname) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "사용 가능",
                            payload = ApiResponseBody.ok(NicknameCheckResponse(available = true)),
                        )
                        add(
                            status = HttpStatus.OK,
                            name = "이미 사용 중",
                            payload = ApiResponseBody.ok(NicknameCheckResponse(available = false)),
                        )
                        add(
                            status = HttpStatus.BAD_REQUEST,
                            name = "닉네임 형식 검증 실패",
                            payload =
                                ApiResponseBody.fail<Unit>(
                                    CommonErrorCode.INVALID_INPUT,
                                    // 쿼리 파라미터 바인딩 @Valid 위반도 GlobalExceptionHandler.detailOf 가 위반 필드의 메시지를 그대로 detail 로 내린다.
                                    detail = NicknameCheckRequest.NICKNAME_SIZE_MESSAGE,
                                ),
                        )
                        unauthorized()
                    }
            }
            operation
        }

    // 발급 example 의 raw key — 실제 발급 형식(items/raw/{UUID}.{ext})과 같은 모양으로 둔다.

    private val SAMPLE_IMAGE_KEY = "items/raw/550e8400-e29b-41d4-a716-446655440000.png"


    private fun sampleUser(): UserResponse =
        UserResponse(
            id = SAMPLE_USER_ID,
            nickname = "뛰어다니는 강아지",
            profileImage = "https://piki-assets.s3.ap-northeast-2.amazonaws.com/defaults/user-profile-1.png",
            identityType = IdentityType.GUEST,
        )

    private fun sampleMyProfile(): MyProfileResponse =
        MyProfileResponse(
            id = SAMPLE_USER_ID,
            nickname = "뛰어다니는 강아지",
            profileImage = "https://api.dicebear.com/9.x/bottts/svg?seed=$SAMPLE_USER_ID",
            identityType = IdentityType.MEMBER,
            email = "user@gmail.com",
        )

    companion object {
        private val SAMPLE_USER_ID: UUID = UUID.fromString("8f1a3c2b-9d44-4e2a-9b12-1a2b3c4d5e6f")
    }
}
