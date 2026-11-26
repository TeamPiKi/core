package com.depromeet.piki.friendship.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.friendship.controller.dto.FriendListResponse
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
private const val GUEST = "권한 없음 (게스트는 친구 기능을 쓸 수 없음 — code: FRIENDSHIP-001)"
private const val DELETED_USER = "요청자 본인이 탈퇴한 계정 (JWT 는 아직 유효하나 계정이 탈퇴 상태 — code: USER-003)"
private const val SELF = "자기 자신을 친구로 추가 (code: FRIENDSHIP-002)"

// 상대의 부재와 탈퇴를 가르지 않는다 — 가르면 "그 계정이 있었다" 가 드러난다
private const val TARGET_NOT_FOUND = "상대를 찾을 수 없음 (없는 사용자 · 탈퇴한 사용자 — code: USER-001)"

@Tag(name = "Friendship", description = "단방향 친구 추가·삭제·목록 API")
interface FriendshipApi {
    @Operation(
        summary = "친구 추가",
        description =
            "상대를 내 친구 목록에 넣는다. 요청·수락이 없는 단방향이라 상대의 목록은 바뀌지 않고 알림도 가지 않는다.\n\n" +
                "- 이미 친구면 아무것도 바꾸지 않고 그대로 성공한다(멱등). 같은 탭을 두 번 눌러도 실패가 아니다.\n" +
                "- 상대가 나를 추가했는지와 무관하다. 서로 추가하면 각자 목록에 한 줄씩 생긴다.\n" +
                "- 상대가 없거나 탈퇴했으면 404 로 같다. 둘을 가르지 않는다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "추가 (이미 친구였어도 같은 응답)",
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
    fun addFriend(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "친구로 추가할 사용자 id") friendId: UUID,
    ): ApiResponseBody<Unit>

    @Operation(
        summary = "친구 삭제",
        description =
            "상대를 내 친구 목록에서 뺀다. 상대에게 알림은 가지 않는다.\n\n" +
                "- 친구가 아니었어도 그대로 성공한다(멱등). 지울 것이 없을 뿐이다.\n" +
                "- 상대가 탈퇴했어도 성공한다. 그 경우 탈퇴 처리가 이미 관계를 지웠다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "삭제 (친구가 아니었어도 같은 응답)",
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
    fun removeFriend(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "친구에서 뺄 사용자 id") friendId: UUID,
    ): ApiResponseBody<Unit>

    @Operation(
        summary = "내 친구 목록",
        description =
            "내가 추가한 친구들을 최근 추가순으로 돌려준다.\n\n" +
                "- 친구 수는 이 응답에만 있다. 다른 사람 프로필에는 노출하지 않는다.\n" +
                "- 행마다 공개 폴더 수와 그 폴더에 든 위시 수가 붙는다. 비공개 폴더와 거기에만 든 위시는 세지 않는다.\n" +
                "- 상대가 프로필을 비공개로 두면 두 수가 없다(`folderCount` · `wishCount` 키 생략). " +
                "앱은 수 대신 비공개 표시를 띄운다.\n" +
                "- 탈퇴한 친구는 목록에서 빠진다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 (친구가 없으면 friendCount=0, friends=[])",
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
    fun listFriends(
        @Parameter(hidden = true) userId: UUID,
    ): ApiResponseBody<FriendListResponse>
}
