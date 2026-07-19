package com.depromeet.piki.auth.controller

import com.depromeet.piki.auth.controller.dto.OAuthLoginRequest
import com.depromeet.piki.auth.controller.dto.OAuthLoginResponse
import com.depromeet.piki.auth.web.ClientType
import com.depromeet.piki.common.response.ApiResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import java.util.UUID

@Tag(name = "Auth", description = "인증 API")
interface OAuthApi {
    @Operation(
        summary = "소셜 로그인",
        description =
            "`kakao` / `google` 소셜 로그인. 처음 보는 소셜이면 **MEMBER 로 가입**(닉네임 자동 fill, 이후 수정 가능)하고, 기존이면 로그인한다.\n\n" +
                "**요청 방식 (provider 버전별)**\n\n" +
                "| 버전 | 보내는 값 |\n" +
                "|---|---|\n" +
                "| v1 (웹) | body 에 `code` + `redirectUri` |\n" +
                "| v2 (SDK) | body 에 `accessToken` |\n\n" +
                "- **게스트 승격** — 게스트 토큰을 함께 보내면 그 게스트 계정에 소셜을 연결+승격해 위시·토너먼트 데이터를 이어준다.\n" +
                "- **토큰 전달** — 응답 토큰은 기본 HttpOnly 쿠키로 내려가며, `X-Client-Type: app` 일 때만 body 로 내린다.\n" +
                "- **CSRF** — v1 웹 흐름은 `GET /auth/{provider}/url` 로 발급받은 `state` 를 body 에 함께 보내면 검증이 활성화된다.",
    )
    // 만료/미인증 클라이언트도 호출하므로 인증 없이 진입 (SecurityConfig 의 permitAll).
    @SecurityRequirements
    @Parameter(
        name = ClientType.HEADER,
        `in` = ParameterIn.HEADER,
        required = false,
        description = "클라이언트 종류. app 이면 body 로 토큰을 받는다. 그 외·미설정은 HttpOnly 쿠키(기본).",
        schema = Schema(allowableValues = ["web", "app"]),
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "로그인/가입 성공 (기본: Set-Cookie + body 토큰 null / app: body 토큰)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description =
                    "잘못된 요청\n\n" +
                        "- `code`+`redirectUri` 도 `accessToken` 도 없음 (`OAUTH-002`) / 본문 검증 실패(두 흐름 동시·둘 다 공백)는 `COMMON-INVALID-INPUT`\n" +
                        "- 지원하지 않는 provider (`OAUTH-003`)\n" +
                        "- provider 인가코드(`code`)가 만료/재사용/무효 (`OAUTH-005`) — 재로그인으로 새 code 를 받아 재시도",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "401",
                description =
                    "인증 실패\n\n" +
                        "- `state` 검증 실패 (만료·미발급·이미 소비됨) (`OAUTH-004`) — `GET /auth/{provider}/url` 로 재발급 필요\n" +
                        "- provider access token 무효/만료 (`OAUTH-006`) — 재로그인으로 새 토큰을 받아 재시도",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "502",
                description =
                    "소셜 제공자 장애 (`OAUTH-001`) — 네트워크·5xx·점검·user_info 조회 실패·미지 에러코드 fallback. 동일 요청으로 재시도 가능.",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "500",
                description =
                    "우리 OAuth 설정·요청 오류 (`OAUTH-007`) — client_id/secret·client_secret JWT·scope·필수 인자 누락 등을 provider 가 거부. 상류 장애가 아니라 서버 버그라 재시도 무의미, 서버 설정 수정 필요.",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun login(
        @Parameter(description = "소셜 제공자", example = "kakao", schema = Schema(allowableValues = ["kakao", "google"]))
        provider: String,
        request: OAuthLoginRequest,
        @Parameter(hidden = true) currentUserId: UUID?,
    ): ApiResponseBody<OAuthLoginResponse>
}
