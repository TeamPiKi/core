package com.depromeet.piki.auth.controller

import com.depromeet.piki.auth.controller.dto.GuestCreateResponse
import com.depromeet.piki.auth.controller.dto.LogoutResponse
import com.depromeet.piki.auth.controller.dto.TokenRefreshRequest
import com.depromeet.piki.auth.controller.dto.TokenRefreshResponse
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
interface AuthApi {
    @Operation(
        summary = "게스트 생성",
        description =
            "새 **GUEST** User 를 생성하고 JWT 토큰 쌍(access·refresh)을 발급한다.\n\n" +
                "토큰 전달 방식은 `X-Client-Type` 헤더로 갈린다.\n\n" +
                "| X-Client-Type | 토큰 전달 | body 토큰 |\n" +
                "|---|---|---|\n" +
                "| 미설정 · `web` | `Set-Cookie` HttpOnly 쿠키 2개 (secure by default) | `null` |\n" +
                "| `app` | 응답 body (네이티브 secure storage) | 포함 |",
    )
    // 인증 없이 호출하는 진입점 (SecurityConfig 의 permitAll). 글로벌 Bearer 요구를 해제한다.
    @SecurityRequirements
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "게스트 생성 성공 (기본: Set-Cookie 2개 + body 토큰 null / app: body 토큰)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            // 서버 사정(닉네임 풀 소진)이지만 handleUnexpected 의 일반 500 이 아니라 HttpMappable 이 던지는 명시 계약이라,
            // 정상 요청으로도 닿을 수 있다. 클라이언트는 재시도해도 소진 상태면 계속 실패하므로 안내가 필요하다.
            ApiResponse(
                responseCode = "500",
                description = "게스트 닉네임 자동 생성 실패 (닉네임 풀 소진)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    @Parameter(
        name = ClientType.HEADER,
        `in` = ParameterIn.HEADER,
        required = false,
        description = "클라이언트 종류. app 이면 body 로 토큰을 받는다(네이티브 secure storage). 그 외·미설정은 HttpOnly 쿠키로 받고 body 토큰은 null(기본, secure by default).",
        schema = Schema(allowableValues = ["web", "app"]),
    )
    fun createGuest(): ApiResponseBody<GuestCreateResponse>

    @Operation(
        summary = "토큰 갱신",
        description =
            "리프레시 토큰을 검증하고 새 access·refresh 토큰 쌍을 발급(회전)한다. " +
                "만료된 access token 클라이언트도 호출할 수 있도록 인증 없이 진입한다.\n\n" +
                "- **입력** — 리프레시 토큰을 `refresh_token` 쿠키(기본) 또는 요청 body(app) 어느 쪽으로든 받는다.\n" +
                "- **출력** — 기본은 새 토큰을 `Set-Cookie` 로 회전하고 body 토큰은 `null`, `X-Client-Type: app` 일 때만 body 로 내린다.",
    )
    // 만료된 액세스 토큰을 가진 클라이언트도 호출해야 하므로 인증 없이 진입 (SecurityConfig 의 permitAll).
    @SecurityRequirements
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "토큰 갱신 성공 (기본: Set-Cookie 회전 + body 토큰 null / app: body 토큰)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "리프레시 토큰 미입력 (쿠키·body 모두 없음) · body 의 refreshToken 이 공백",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "401",
                description = "유효하지 않은 리프레시 토큰 (파싱 불가 · 만료 · Redis 값 불일치 · 탈퇴 유저)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    @Parameter(
        name = ClientType.HEADER,
        `in` = ParameterIn.HEADER,
        required = false,
        description = "클라이언트 종류. app 이면 body 로 받는다. 그 외·미설정은 새 토큰을 Set-Cookie 로 회전하고 body 토큰은 null(기본).",
        schema = Schema(allowableValues = ["web", "app"]),
    )
    fun refresh(
        request: TokenRefreshRequest?,
        @Parameter(hidden = true) cookieRefreshToken: String?,
    ): ApiResponseBody<TokenRefreshResponse>

    @Operation(
        summary = "로그아웃",
        description =
            "리프레시 토큰을 삭제해 로그아웃 처리하고, 토큰 쿠키를 만료(`Max-Age=0`)시킨다.\n\n" +
                "- 쿠키 만료는 클라이언트 종류와 무관하게 **항상** 내려간다 (웹 쿠키가 확실히 삭제되도록).\n" +
                "- APP 은 쿠키를 쓰지 않아 영향 없다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "로그아웃 성공 (토큰 쿠키 만료)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "401",
                description = "미인증 (JWT 토큰 없음 또는 유효하지 않음)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun logout(
        @Parameter(hidden = true) userId: UUID,
    ): ApiResponseBody<LogoutResponse>
}
