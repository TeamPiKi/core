package com.depromeet.piki.user.controller

import com.depromeet.piki.auth.controller.dto.GuestCreateResponse
import com.depromeet.piki.auth.service.dto.TokenPair
import com.depromeet.piki.common.openapi.OpenApiObjectMapper
import com.depromeet.piki.common.openapi.binds
import com.depromeet.piki.common.openapi.examples
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.common.response.PageResponse
import com.depromeet.piki.user.controller.dto.DevUserSummaryResponse
import com.depromeet.piki.user.controller.dto.UserResponse
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.UserException
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import java.util.UUID

@Configuration
class DevUserApiExamples(
    private val openApiObjectMapper: OpenApiObjectMapper,
) {
    @Bean
    fun devUserOpenApiExamples(): OperationCustomizer =
        OperationCustomizer { operation, handlerMethod ->
            when {
                handlerMethod.binds(DevUserController::listUsers) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "유저 목록 (다음 페이지 있음)",
                            payload =
                                ApiResponseBody.ok(
                                    data =
                                        listOf(
                                            DevUserSummaryResponse(
                                                userId = UUID.fromString("8f1a3c2b-9d44-4e2a-9b12-1a2b3c4d5e6f"),
                                                nickname = "뛰어다니는 강아지",
                                            ),
                                            DevUserSummaryResponse(
                                                userId = UUID.fromString("3b9c1d2e-4f5a-4b6c-8d7e-9f0a1b2c3d4e"),
                                                nickname = "홍길동",
                                            ),
                                        ),
                                    pageResponse = PageResponse(nextCursor = "1", hasNext = true),
                                ),
                        )
                    }

                handlerMethod.binds(DevUserController::getUser) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(
                            status = HttpStatus.OK,
                            name = "유저 + 토큰 발급 성공",
                            payload =
                                ApiResponseBody.ok(
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
                        // 실제 응답은 UserException.notFound()/deletedUser() → USER-001/USER-003 이므로
                        // 예외에서 직접 example 을 만들어 code·detail 을 실제와 일치시킨다.
                        add(UserException.notFound(), name = "userId 에 해당하는 유저 없음")
                        add(UserException.deletedUser(), name = "탈퇴된 유저 — 토큰 발급 거부")
                    }
            }
            operation
        }
}
