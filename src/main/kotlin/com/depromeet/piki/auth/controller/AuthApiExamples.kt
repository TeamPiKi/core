package com.depromeet.piki.auth.controller

import com.depromeet.piki.auth.controller.dto.DevUserCreateRequest
import com.depromeet.piki.auth.controller.dto.GuestCreateResponse
import com.depromeet.piki.auth.controller.dto.LogoutResponse
import com.depromeet.piki.auth.controller.dto.TokenRefreshResponse
import com.depromeet.piki.auth.exception.AuthException
import com.depromeet.piki.auth.service.dto.TokenPair
import com.depromeet.piki.common.exception.CommonErrorCode
import com.depromeet.piki.common.openapi.OpenApiObjectMapper
import com.depromeet.piki.common.openapi.binds
import com.depromeet.piki.common.openapi.examples
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.user.controller.dto.UserResponse
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.UserException
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import java.util.UUID

@Configuration
class AuthApiExamples(
    private val openApiObjectMapper: OpenApiObjectMapper,
) {
    @Bean
    fun authOpenApiExamples(): OperationCustomizer =
        OperationCustomizer { operation, handlerMethod ->
            when {
                handlerMethod.binds(AuthController::createGuest) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.CREATED,
                            name = "게스트 생성 성공 (APP — body 토큰)",
                            payload =
                                ApiResponseBody.created(
                                    GuestCreateResponse(
                                        user =
                                            UserResponse(
                                                id = UUID.fromString("8f1a3c2b-9d44-4e2a-9b12-1a2b3c4d5e6f"),
                                                nickname = "뛰어다니는 강아지",
                                                profileImage =
                                                    "https://piki-assets.s3.ap-northeast-2.amazonaws.com/defaults/user-profile-1.png",
                                                identityType = IdentityType.GUEST,
                                            ),
                                        tokenPair =
                                            TokenPair(
                                                accessToken = "eyJhbGciOiJIUzI1NiJ9.access",
                                                refreshToken = "eyJhbGciOiJIUzI1NiJ9.refresh",
                                            ),
                                    ),
                                ),
                        )
                        add(UserException.nicknameGenerationFailed(), name = "닉네임 풀 소진")
                    }

                handlerMethod.binds(AuthController::refresh) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "토큰 갱신 성공 (APP — body 토큰)",
                            payload =
                                ApiResponseBody.ok(
                                    TokenRefreshResponse(
                                        tokenPair =
                                            TokenPair(
                                                accessToken = "eyJhbGciOiJIUzI1NiJ9.access",
                                                refreshToken = "eyJhbGciOiJIUzI1NiJ9.refresh",
                                            ),
                                    ),
                                ),
                        )
                        add(AuthException.refreshTokenRequired(), name = "리프레시 토큰 미입력")
                        // 컨트롤러가 직접 던지는 401 도메인 예외(만료·위변조·이미 사용된 토큰)라 detail 이 있으므로
                        // Security 필터 단의 unauthorized() 가 아니라 예외를 그대로 받는다.
                        add(AuthException.invalidToken(), name = "유효하지 않은 토큰")
                    }

                handlerMethod.binds(AuthController::logout) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "로그아웃 성공",
                            payload = ApiResponseBody.ok(LogoutResponse()),
                        )
                        unauthorized()
                    }

                handlerMethod.binds(DevAuthController::createDevUser) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.CREATED,
                            name = "MEMBER 생성 성공",
                            payload =
                                ApiResponseBody.created(
                                    GuestCreateResponse(
                                        user =
                                            UserResponse(
                                                id = UUID.fromString("3b9c1d2e-4f5a-4b6c-8d7e-9f0a1b2c3d4e"),
                                                nickname = "홍길동",
                                                profileImage =
                                                    "https://piki-assets.s3.ap-northeast-2.amazonaws.com/defaults/user-profile-2.png",
                                                identityType = IdentityType.MEMBER,
                                            ),
                                        tokenPair =
                                            TokenPair(
                                                accessToken = "eyJhbGciOiJIUzI1NiJ9.access",
                                                refreshToken = "eyJhbGciOiJIUzI1NiJ9.refresh",
                                            ),
                                    ),
                                ),
                        )
                        add(
                            status = HttpStatus.BAD_REQUEST,
                            name = "닉네임 미입력",
                            payload =
                                ApiResponseBody.fail<Unit>(
                                    CommonErrorCode.INVALID_INPUT,
                                    // Bean Validation 위반은 GlobalExceptionHandler.detailOf 가 위반 필드의 메시지를 그대로 detail 로 내린다.
                                    detail = DevUserCreateRequest.NICKNAME_REQUIRED_MESSAGE,
                                ),
                        )
                        // 이 요청 DTO 는 @NotBlank 만 두고 @Size 가 없어, 길이·예약 prefix 는 User.validateNickname 이 잡는다.
                        add(UserException.nicknameTooLong(), name = "닉네임 10자 초과")
                        add(UserException.nicknameReserved(), name = "'탈퇴' 예약 prefix 로 시작하는 닉네임")
                        unauthorized()
                        forbidden("GUEST 권한 없음 (MEMBER 토큰으로 호출 불가)")
                        add(UserException.duplicateNickname(), name = "이미 사용 중인 닉네임")
                    }

                handlerMethod.binds(DevAuthController::issueTokenForUser) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.CREATED,
                            name = "토큰 발급 성공",
                            payload =
                                ApiResponseBody.created(
                                    GuestCreateResponse(
                                        user =
                                            UserResponse(
                                                id = UUID.fromString("3b9c1d2e-4f5a-4b6c-8d7e-9f0a1b2c3d4e"),
                                                nickname = "홍길동",
                                                profileImage =
                                                    "https://piki-assets.s3.ap-northeast-2.amazonaws.com/defaults/user-profile-2.png",
                                                identityType = IdentityType.MEMBER,
                                            ),
                                        tokenPair =
                                            TokenPair(
                                                accessToken = "eyJhbGciOiJIUzI1NiJ9.access",
                                                refreshToken = "eyJhbGciOiJIUzI1NiJ9.refresh",
                                            ),
                                    ),
                                ),
                        )
                        add(UserException.notFound(), name = "존재하지 않는 user")
                        add(UserException.deletedUser(), name = "탈퇴된 user")
                        unauthorized()
                        forbidden("GUEST 권한 없음 (MEMBER 토큰으로 호출 불가)")
                    }
            }
            operation
        }

}
