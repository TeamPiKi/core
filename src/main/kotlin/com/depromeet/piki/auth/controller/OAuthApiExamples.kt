package com.depromeet.piki.auth.controller

import com.depromeet.piki.auth.controller.dto.OAuthLoginRequest
import com.depromeet.piki.auth.controller.dto.OAuthLoginResponse
import com.depromeet.piki.auth.infrastructure.oauth.OAuthException
import com.depromeet.piki.auth.service.dto.TokenPair
import com.depromeet.piki.common.exception.CommonErrorCode
import com.depromeet.piki.common.openapi.OpenApiObjectMapper
import com.depromeet.piki.common.openapi.binds
import com.depromeet.piki.common.openapi.examples
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.user.controller.dto.UserResponse
import com.depromeet.piki.user.domain.IdentityType
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import java.util.UUID

@Configuration
class OAuthApiExamples(
    private val openApiObjectMapper: OpenApiObjectMapper,
) {
    @Bean
    fun oAuthOpenApiExamples(): OperationCustomizer =
        OperationCustomizer { operation, handlerMethod ->
            if (handlerMethod.binds(OAuthController::login)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(
                        status = HttpStatus.OK,
                        name = "로그인/가입 성공 (app — body 토큰)",
                        payload =
                            ApiResponseBody.ok(
                                OAuthLoginResponse(
                                    user =
                                        UserResponse(
                                            id = UUID.fromString("8f1a3c2b-9d44-4e2a-9b12-1a2b3c4d5e6f"),
                                            nickname = "뛰어다니는 강아지",
                                            profileImage = "https://lh3.googleusercontent.com/profile.jpg",
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
                        name = "요청 본문 검증 실패 (두 흐름 동시 전달 또는 둘 다 누락/공백)",
                        payload =
                            ApiResponseBody.fail<Unit>(
                                CommonErrorCode.INVALID_INPUT,
                                // @AssertTrue 위반은 GlobalExceptionHandler.detailOf 가 위반 필드의 메시지를 그대로 detail 로 내린다.
                                detail = OAuthLoginRequest.VALID_FLOW_MESSAGE,
                            ),
                    )
                    // OAUTH-002(invalidRequest)는 example 을 두지 않는다 — 이 엔드포인트에서 도달 불가능하기 때문.
                    // 컨트롤러의 @Valid 가 OAuthLoginRequest.validFlow(v1 xor v2)를 먼저 강제하므로, invalidRequest 를
                    // 터뜨릴 입력(둘 다 누락/공백)은 그 전에 위 COMMON-INVALID-INPUT 으로 걸린다. 서비스의 invalidRequest 는
                    // 입력 경계를 안 거치는 호출자를 위한 중복방어로 남고, 못 닿는 응답은 문서화하지 않는다
                    // (CLAUDE.md 응답 전수 문서화 — "닿는다 → 문서화 대상, 못 닿는다 → 제외").
                    add(OAuthException.unsupportedProvider(), name = "지원하지 않는 provider")
                    add(OAuthException.invalidGrant(), name = "인가코드(code) 만료/재사용/무효")
                    add(OAuthException.invalidState(), name = "state 검증 실패 (만료 또는 미발급)")
                    add(OAuthException.invalidProviderToken(), name = "provider access token 무효/만료 (재로그인 필요)")
                    add(OAuthException.providerError(dummyCause), name = "소셜 제공자 장애 (RETRYABLE — 재시도 가능)")
                    add(OAuthException.misconfigured(dummyCause), name = "우리 OAuth 설정/요청 오류 (SERVER_ERROR — 재시도 무의미)")
                }
            }
            operation
        }

    // OAuthException.providerError/misconfigured 는 cause 를 요구하지만, example 헬퍼는 message·category·status 만
    // 사용한다(GlobalExceptionHandler.handleBaseException 과 동일). 따라서 이 cause 는 payload 에 영향을 주지 않는 더미다.
    private val dummyCause = IllegalArgumentException("example")
}
