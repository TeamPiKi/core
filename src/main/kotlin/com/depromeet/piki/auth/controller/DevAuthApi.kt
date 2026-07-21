package com.depromeet.piki.auth.controller

import com.depromeet.piki.auth.controller.dto.DevUserCreateRequest
import com.depromeet.piki.auth.controller.dto.GuestCreateResponse
import com.depromeet.piki.auth.web.ClientType
import com.depromeet.piki.common.response.ApiResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import java.util.UUID

@Tag(name = "Dev", description = "개발·테스트 전용 API (운영 환경 비활성화)")
interface DevAuthApi {
    @Operation(
        summary = "개발용 MEMBER 생성",
        description = "OAuth 없이 MEMBER User 를 생성하고 JWT 토큰 쌍을 발급한다. 인증 토큰(GUEST·MEMBER)으로 호출한다. OAuth 통합 전까지의 임시 endpoint.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "MEMBER 생성 성공",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "닉네임 미입력 또는 형식 오류",
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
            ApiResponse(
                responseCode = "409",
                description = "이미 사용 중인 닉네임",
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
    fun createDevUser(request: DevUserCreateRequest): ApiResponseBody<GuestCreateResponse>

    @Operation(
        summary = "기존 user 의 토큰 발급 (임의 user 가장)",
        description = "이미 존재하는 user (GUEST·MEMBER 모두)의 access·refresh 토큰을 발급한다. " +
            "개발·테스트에서 특정 user 시나리오를 재현할 때 사용한다.\n\n" +
            "- 인증 토큰(GUEST·MEMBER)으로 호출한다.\n" +
            "- OAuth 통합 전까지의 임시 endpoint 로, 다른 dev API 들과 함께 운영에서 차단 예정.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "토큰 발급 성공",
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
            ApiResponse(
                responseCode = "404",
                description = "userId 에 해당하는 user 없음",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "탈퇴(soft delete) 된 user — 토큰 발급 거부",
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
    fun issueTokenForUser(userId: UUID): ApiResponseBody<GuestCreateResponse>
}
