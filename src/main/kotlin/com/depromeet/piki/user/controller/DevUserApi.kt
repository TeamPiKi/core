package com.depromeet.piki.user.controller

import com.depromeet.piki.auth.controller.dto.GuestCreateResponse
import com.depromeet.piki.auth.web.ClientType
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.user.controller.dto.DevUserSummaryResponse
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
interface DevUserApi {
    @Operation(
        summary = "유저 목록 조회",
        description = "등록된 모든 유저의 userId 와 nickname 을 반환한다. 개발 편의용.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "유저 목록 반환",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun listUsers(size: Int, cursor: String?): ApiResponseBody<List<DevUserSummaryResponse>>

    @Operation(
        summary = "단건 유저 조회",
        description = "userId 로 유저 정보와 AT·RT 를 발급해 반환한다. 개발 편의용.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "유저 정보 및 AT·RT 반환",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "userId 에 해당하는 유저 없음",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "탈퇴(soft delete) 된 유저 — 토큰 발급 거부",
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
    fun getUser(userId: UUID): ApiResponseBody<GuestCreateResponse>
}
