package com.depromeet.piki.explore.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.explore.controller.dto.ExploreRecentResponse
import com.depromeet.piki.explore.controller.dto.ExploreWishDetailResponse
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
interface ExploreApi {
    @Operation(
        summary = "방금 담긴 위시",
        description =
            "프로필이 공개인 사람들이 최근에 담은 위시를 최신순으로 돌려준다.\n\n" +
                "- **비로그인도 호출할 수 있다.**\n" +
                "- 실시간 목록이 아니다. 서버가 약 1분마다 목록을 통째로 다시 만들고, 그 시각이 `updatedAt` 이다. " +
                "새로 담긴 위시는 다음 갱신에 나타난다.\n" +
                "- 폴더는 노출과 무관하다. 프로필을 비공개로 둔 사람의 위시만 빠진다.\n" +
                "- 같은 상품을 여러 사람이 담았으면 가장 최근에 담긴 한 장만 나온다.\n" +
                "- 이름·가격·이미지가 모두 있는 상품만 나온다.\n" +
                "- 토큰이 있으면 차단 관계(어느 방향이든)인 사람의 위시가 빠지고, `saved` 에 내가 이미 가진 상품인지가 실린다.\n" +
                "- 다음 쪽은 `pageResponse.nextCursor` 를 `cursor` 로 넘긴다. 최근에 담긴 상품 100개까지만 내려간다.\n" +
                "- 목록이 아직 만들어지지 않았으면 `updatedAt=null` · `wishes=[]` 다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 (목록이 아직 없으면 updatedAt=null · wishes=[])",
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
    fun getRecent(
        @Parameter(hidden = true) viewerId: UUID?,
        @Parameter(description = "직전 쪽의 pageResponse.nextCursor. 첫 쪽은 비운다") cursor: String?,
        @Parameter(description = "쪽 크기. 기본 20, 1 에서 50 사이로 보정된다") size: Int?,
    ): ApiResponseBody<ExploreRecentResponse>

    @Operation(
        summary = "다른 사람이 담은 위시 상세",
        description =
            "탐색 카드나 공개 폴더의 위시 한 건을 돌려준다.\n\n" +
                "- **비로그인도 호출할 수 있다.**\n" +
                "- 목록과 달리 호출한 순간의 상태로 답한다.\n" +
                "- **상품은 항상 내려간다.** 가리는 것은 담은 사람과 폴더뿐이다.\n" +
                "- 담은 사람이 프로필을 비공개로 두었거나 보는 사람과 차단 관계(어느 방향이든)면 `owner=null` 이다. " +
                "두 경우를 가르지 않는다. 가르면 차단 사실이 드러난다.\n" +
                "- `owner.folder` 는 그 위시가 든 공개 폴더다. 비공개 폴더에만 들었거나 폴더에 없으면 null 이고, " +
                "비공개 폴더의 이름과 id 는 내려가지 않는다.\n" +
                "- `owner` 가 가려진 응답의 상품 값에는 담은 사람이 직접 입력한 이름·가격·이미지가 쓰이지 않는다. " +
                "그런 값밖에 없는 위시는 404 다.\n" +
                "- 내 위시를 내가 보면 프로필이 비공개여도 `owner` 가 `relation=SELF` 로 내려간다. 이때도 `folder` 는 공개 폴더만이다.\n" +
                "- `owner.userId` 로 프로필을, `owner.folder.folderId` 로 공개 폴더 상세를 연다.\n" +
                "- 공개 폴더 상세의 PARSING·FAILED 카드는 이 API 로 보내지 않는다. 상품이 정해지지 않아 404 다.\n" +
                "- `savedUserCount` 는 이 상품을 위시에 담은 사람 수다. 프로필이 비공개인 사람도 수로만 들어간다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 (담은 사람을 볼 수 없으면 owner=null)",
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
                    "위시를 찾을 수 없음 (없는 위시 · 지운 위시 · 상품이 아직 정해지지 않은 위시 · " +
                        "담은 사람이 가려졌는데 그 사람이 직접 입력한 값밖에 없는 위시 — code: WISH-004)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun getWish(
        @Parameter(hidden = true) viewerId: UUID?,
        @Parameter(description = "볼 위시 id") wishId: Long,
    ): ApiResponseBody<ExploreWishDetailResponse>
}
