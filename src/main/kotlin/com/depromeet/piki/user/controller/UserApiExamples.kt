package com.depromeet.piki.user.controller

import com.depromeet.piki.common.exception.CommonErrorCode
import com.depromeet.piki.common.openapi.OpenApiObjectMapper
import com.depromeet.piki.common.openapi.binds
import com.depromeet.piki.common.openapi.examples
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.common.storage.ImageStorageException
import com.depromeet.piki.user.controller.dto.MyProfileResponse
import com.depromeet.piki.user.controller.dto.NicknameCheckRequest
import com.depromeet.piki.user.controller.dto.NicknameCheckResponse
import com.depromeet.piki.user.controller.dto.UserResponse
import com.depromeet.piki.user.controller.dto.UserUpdateRequest
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.UserException
import com.depromeet.piki.user.service.DefaultProfileImages
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import java.util.UUID

@Configuration
class UserApiExamples(
    private val openApiObjectMapper: OpenApiObjectMapper,
    // example 의 기본 아바타 URL 을 실제 발급 로직에서 끌어온다 — 하드코딩하면 env 별 버킷과 어긋나
    // docs 가 어느 환경에서도 열리지 않는 주소를 보여준다(기존 piki-assets 가 그랬다).
    private val defaultProfileImages: DefaultProfileImages,
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
                                    // @ModelAttribute(multipart) Bean Validation 위반도 GlobalExceptionHandler.detailOf 가 위반 필드의 메시지를 그대로 detail 로 내린다.
                                    detail = UserUpdateRequest.NICKNAME_SIZE_MESSAGE,
                                ),
                        )
                        // 빈 파일은 같은 detail (클라 액션 동일: 파일 재첨부)
                        add(UserException.emptyProfileImage(), name = "빈 이미지 파일")
                        // 타입 미지정·미지원 형식은 같은 detail (클라 액션 동일: 허용 형식으로 변경)
                        add(UserException.unsupportedProfileImageType(), name = "지원하지 않는 이미지 형식")
                        // 선언한 Content-Type 과 실제 파일 시그니처가 어긋남 (헤더 위조·손상)
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
                        // multipart 한도 초과 — ResponseEntityExceptionHandler 가 표준으로 413 처리하고
                        // handleExceptionInternal 이 ApiResponseBody(category=INVALID_INPUT, 기본 detail)로 감싼다.
                        add(
                            status = HttpStatus.PAYLOAD_TOO_LARGE,
                            name = "파일 크기 초과",
                            payload = ApiResponseBody.fail<Unit>(CommonErrorCode.INVALID_INPUT),
                        )
                        add(ImageStorageException.uploadFailed(), name = "이미지 저장소(S3) 업로드 실패")
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

    private fun sampleUser(): UserResponse =
        UserResponse(
            id = SAMPLE_USER_ID,
            nickname = "뛰어다니는 강아지",
            profileImage = defaultProfileImages.urlOf(1),
            identityType = IdentityType.GUEST,
        )

    private fun sampleMyProfile(): MyProfileResponse =
        MyProfileResponse(
            id = SAMPLE_USER_ID,
            nickname = "뛰어다니는 강아지",
            profileImage = defaultProfileImages.urlOf(2),
            identityType = IdentityType.MEMBER,
            email = "user@gmail.com",
        )

    companion object {
        private val SAMPLE_USER_ID: UUID = UUID.fromString("8f1a3c2b-9d44-4e2a-9b12-1a2b3c4d5e6f")
    }
}
