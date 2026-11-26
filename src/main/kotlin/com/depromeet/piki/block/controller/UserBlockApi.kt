package com.depromeet.piki.block.controller

import com.depromeet.piki.block.controller.dto.BlockListResponse
import com.depromeet.piki.common.response.ApiResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import java.util.UUID

private const val UNAUTHORIZED = "미인증 (JWT 토큰 없음 또는 유효하지 않음)"
private const val GUEST = "권한 없음 (게스트는 차단 기능을 쓸 수 없음 — code: BLOCK-001)"
private const val DELETED_USER = "요청자 본인이 탈퇴한 계정 (JWT 는 아직 유효하나 계정이 탈퇴 상태 — code: USER-003)"
private const val SELF = "자기 자신을 차단 (code: BLOCK-002)"

// 상대의 부재와 탈퇴를 가르지 않는다 — 가르면 "그 계정이 있었다" 가 드러난다
private const val TARGET_NOT_FOUND = "상대를 찾을 수 없음 (없는 사용자 · 탈퇴한 사용자 — code: USER-001)"

@Tag(name = "Block", description = "사용자 차단·해제·차단 목록 API")
interface UserBlockApi {
    @Operation(
        summary = "사용자 차단",
        description =
            "상대를 차단한다. 차단하면 서로의 폴더·위시를 볼 수 없다.\n\n" +
                "- 이미 차단했으면 아무것도 바꾸지 않고 그대로 성공한다(멱등).\n" +
                "- **두 사람 사이의 친구 관계를 양방향으로 끊는다.** 한쪽만 끊으면 차단당한 사람의 친구 목록에 " +
                "내가 남아 공개 폴더 수를 계속 보게 된다.\n" +
                "- 상대에게 알림은 가지 않는다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "차단 (이미 차단했어도 같은 응답)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = SELF,
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "401",
                description = UNAUTHORIZED,
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "403",
                description = GUEST,
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = TARGET_NOT_FOUND,
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = DELETED_USER,
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun block(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "차단할 사용자 id") blockedId: UUID,
    ): ApiResponseBody<Unit>

    @Operation(
        summary = "차단 해제",
        description =
            "차단을 거둔다.\n\n" +
                "- 차단한 적 없어도 그대로 성공한다(멱등).\n" +
                "- **친구 관계를 되살리지 않는다.** 차단이 끊은 것을 해제가 복원하면 상대는 자기도 모르게 " +
                "다시 친구가 된다. 해제 뒤에는 상대 프로필에 친구 추가 버튼이 보인다.\n" +
                "- 상대가 탈퇴했어도 성공한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "해제 (차단한 적 없어도 같은 응답)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "401",
                description = UNAUTHORIZED,
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "403",
                description = GUEST,
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = DELETED_USER,
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun unblock(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "차단을 거둘 사용자 id") blockedId: UUID,
    ): ApiResponseBody<Unit>

    @Operation(
        summary = "차단 목록",
        description =
            "내가 차단한 사람들을 최근 차단순으로 돌려준다.\n\n" +
                "- 닉네임과 프로필 이미지만 내려간다. 그 둘은 항상 공개이고, 차단한 상대의 폴더·위시 수는 볼 수 없다.\n" +
                "- 탈퇴한 사람은 목록에서 빠진다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 (차단한 사람이 없으면 blocked=[])",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "401",
                description = UNAUTHORIZED,
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "403",
                description = GUEST,
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = DELETED_USER,
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun listBlocked(
        @Parameter(hidden = true) userId: UUID,
    ): ApiResponseBody<BlockListResponse>
}
