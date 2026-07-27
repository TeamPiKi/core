package com.depromeet.piki.notification.fcm.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.notification.fcm.controller.dto.FcmDeviceUnregisterRequest
import com.depromeet.piki.notification.fcm.controller.dto.FcmTokenRegisterRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import java.util.UUID

@Tag(name = "FCM", description = "FCM 푸시 토큰 API")
interface FcmTokenApi {
    @Operation(
        summary = "FCM 토큰 등록/갱신",
        description =
            "현재 인증 유저의 이 기기 FCM 토큰을 등록한다. **upsert(멱등)** 이며 앱 진입·토큰 갱신 시 호출한다.\n\n" +
                "- 같은 기기(`deviceId`)에서 토큰이 회전하면 그 기기 row 의 토큰만 교체한다.\n" +
                "- 다른 사용자가 같은 토큰을 등록하면 이전 소유자 row 를 해제해, 한 토큰은 한 사용자에게만 매핑된다 (로그아웃한 기기로 알림이 새지 않게 함).\n\n" +
                "알림 표시 동의는 OS 권한이 게이트하므로 서버는 동의 여부를 저장하지 않는다 — 발송은 모든 기기에 시도하고 OS 가 표시를 막는다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "등록/갱신 성공",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청 (token 또는 deviceId 가 비어 있음 · 길이 초과)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "미인증 (JWT 토큰 없음 또는 유효하지 않음)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "409",
                description =
                    "탈퇴한 계정의 기기 등록 (`USER-003`) — 정상 흐름에선 탈퇴 access 토큰이 denylist 로 401 에 먼저 막히나, " +
                        "denylist 마킹이 실패한 창(#689)에서 토큰이 필터를 통과하면 여기서 거부된다(활성 유저 잠금 방어, #776).",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
        ],
    )
    fun register(
        @Parameter(hidden = true) userId: UUID,
        request: FcmTokenRegisterRequest,
    ): ApiResponseBody<Unit>

    @Operation(
        summary = "FCM 기기 해제",
        description =
            "현재 인증 유저의 이 기기 등록을 제거한다(로그아웃). **멱등** — 없는 기기를 지워도 성공이다.\n\n" +
                "- 인증이 필요하므로 로그아웃 시퀀스에서 `/auth/logout` 보다 **먼저**(토큰이 아직 유효할 때) 호출해야 한다.\n" +
                "- 로그아웃한 세션·기기로 알림이 새지 않게 하기 위함이다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "해제 성공 (data=null)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청 (deviceId 가 비어 있음 · 길이 초과)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "미인증 (JWT 토큰 없음 또는 유효하지 않음)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
        ],
    )
    fun unregister(
        @Parameter(hidden = true) userId: UUID,
        request: FcmDeviceUnregisterRequest,
    ): ApiResponseBody<Unit>
}
