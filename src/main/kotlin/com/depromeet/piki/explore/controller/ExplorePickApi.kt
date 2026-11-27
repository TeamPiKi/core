package com.depromeet.piki.explore.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.explore.controller.dto.ExplorePickCandidatesResponse
import com.depromeet.piki.explore.controller.dto.ExplorePickDetailResponse
import com.depromeet.piki.explore.controller.dto.ExplorePicksResponse
import com.depromeet.piki.explore.controller.dto.ExploreWishCopyResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import java.util.UUID

@Tag(name = "Explore", description = "탐색 API (조회는 비로그인 가능)")
interface ExplorePickApi {
    @Operation(
        summary = "남들은 뭘 골랐을까?",
        description =
            "사람들이 토너먼트 결승에서 1위로 고른 상품을 최근에 고른 순으로 돌려준다. " +
                "탐색 첫 화면의 가로 섹션은 `size=20` 으로 부른다.\n\n" +
                "- **비로그인도 호출할 수 있다.**\n" +
                "- 1위는 참여자마다 따로 있다. 여럿이 한 토너먼트를 하면 사람마다 1위가 나온다.\n" +
                "- 방금 담긴 위시처럼 서버가 약 1분마다 목록을 통째로 다시 만들고, 그 시각이 `updatedAt` 이다.\n" +
                "- 1위로 고른 사람이 회원이고 프로필이 공개일 때만 나온다. 토너먼트에는 따로 공개 설정이 없다.\n" +
                "- 토너먼트 이름은 만든 사람이 직접 적은 문구 그대로다.\n" +
                "- 상품 값은 쇼핑몰에서 추출한 지금 값이다. 토너먼트 화면처럼 시작 시점의 값이 아니고, " +
                "사람이 직접 고친 값은 쓰지 않는다. 추출한 값이 없는 1위는 나오지 않는다.\n" +
                "- 같은 상품이 여러 번 1위가 되었으면 가장 최근의 한 장만 나온다.\n" +
                "- 토큰이 있으면 차단 관계(어느 방향이든)인 사람의 1위가 빠지고, `saved` 에 내가 이미 가진 상품인지가 실린다.\n" +
                "- 다음 쪽은 `pageResponse.nextCursor` 를 `cursor` 로 넘긴다. 최근 1위 상품 100개까지만 내려간다.\n" +
                "- 목록이 아직 만들어지지 않았으면 `updatedAt=null` · `picks=[]` 다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 (목록이 아직 없으면 updatedAt=null · picks=[])",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청 (cursor 가 숫자가 아님)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun getPicks(
        @Parameter(hidden = true) viewerId: UUID?,
        @Parameter(description = "직전 쪽의 pageResponse.nextCursor. 첫 쪽은 비운다") cursor: String?,
        @Parameter(description = "쪽 크기. 기본 20, 1 에서 50 사이로 보정된다") size: Int?,
    ): ApiResponseBody<ExplorePicksResponse>

    @Operation(
        summary = "토너먼트 1위 상세",
        description =
            "1위 카드 한 장의 상세를 돌려준다.\n\n" +
                "- **비로그인도 호출할 수 있다.**\n" +
                "- 목록과 달리 호출한 순간의 상태로 답한다.\n" +
                "- 위시 상세와 달리 고른 사람이 가려지면(프로필 비공개 · 차단 관계 · 탈퇴) 1위 전체가 404 다. " +
                "1위는 고른 사람의 선택이라 그 사람을 빼고 보여주지 않는다. 내 1위는 프로필이 비공개여도 내게 보인다.\n" +
                "- `picker` 는 위시 상세의 `owner` 와 같은 모양이다. " +
                "`picker.folder` 는 그 사람이 이 상품을 위시로 담아 공개 폴더에 넣었을 때만 있다.\n" +
                "- `tournament.candidateImageUrls` 는 후보 목록과 같은 후보의 썸네일이고 1위가 맨 앞이다. " +
                "추출한 값이 없는 후보는 빠진다.\n" +
                "- 1위를 내 위시로 담을 때는 후보 담기 API 에 응답의 `candidateId` 를 넘긴다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description =
                    "1위를 찾을 수 없음 (없는 1위 · 지운 토너먼트 · 고른 사람이 게스트·탈퇴·프로필 비공개·차단 관계 · " +
                        "1위 상품의 추출값이 없음 — code: EXPLORE-001)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun getPick(
        @Parameter(hidden = true) viewerId: UUID?,
        @Parameter(description = "볼 1위 id") pickId: Long,
    ): ApiResponseBody<ExplorePickDetailResponse>

    @Operation(
        summary = "토너먼트 1위의 후보 목록",
        description =
            "1위가 나온 토너먼트의 후보를 전부 돌려준다. 한 토너먼트의 후보는 32개 이하라 쪽을 나누지 않는다.\n\n" +
                "- **비로그인도 호출할 수 있다.**\n" +
                "- 1위가 맨 앞이고 나머지는 토너먼트에 올라간 순이다.\n" +
                "- 후보를 누가 올렸는지는 내려가지 않는다.\n" +
                "- 상품 값은 1위 목록과 같은 규칙이다. 추출한 값이 없는 후보는 빠진다.\n" +
                "- 볼 수 있는 경우와 404 는 1위 상세와 같다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "1위를 찾을 수 없음 (1위 상세의 404 와 같은 경우 — code: EXPLORE-001)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun getCandidates(
        @Parameter(hidden = true) viewerId: UUID?,
        @Parameter(description = "1위 id") pickId: Long,
    ): ApiResponseBody<ExplorePickCandidatesResponse>

    @Operation(
        summary = "토너먼트 후보를 내 위시로 담기",
        description =
            "후보와 같은 상품을 내 위시에 담는다. 1위 카드·1위 상세·후보 목록의 하트가 모두 이 API 다.\n\n" +
                "- 위시 담기(`POST /api/v1/explore/wishes/{wishId}/copy`)와 같은 동작이다. " +
                "전체 위시에만 저장되고, 이미 가진 상품이면 `alreadySaved=true` 다.\n" +
                "- **담기 취소는 따로 없다.** 카드·상세의 `savedWishId`(또는 이 응답의 `wishId`)로 위시 삭제" +
                "(`DELETE /api/v1/wishlists/{wishId}`)를 부른다. 탐색에 오기 전부터 가진 위시도 같은 위시라 함께 지워지고, " +
                "들어 있던 폴더에서도 빠진다. 지운 뒤에는 같은 `item.id` 의 하트를 모두 비운다. 이미 지운 위시를 다시 지워도 200 이라 남은 카드로는 알 수 없다.\n" +
                "- 후보 목록에 보이는 후보만 담을 수 있다. 다른 토너먼트의 후보 id 를 넘기면 404 다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "담기 성공 (이미 가진 상품이면 alreadySaved=true)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 필요 (토큰 없음·만료). 화면은 로그인 시트를 띄운다",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "403",
                description = "게스트는 담을 수 없음 (code: WISH-001). 화면은 로그인 시트를 띄운다",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "1위나 후보를 찾을 수 없음 (1위 상세의 404 와 같은 경우 · 보이는 후보가 아님 — code: EXPLORE-001)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun copyCandidate(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "1위 id") pickId: Long,
        @Parameter(description = "담을 후보 id") candidateId: Long,
    ): ApiResponseBody<ExploreWishCopyResponse>
}
