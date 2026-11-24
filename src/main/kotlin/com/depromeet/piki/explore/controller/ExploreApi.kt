package com.depromeet.piki.explore.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.explore.controller.dto.ExploreRecentResponse
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
                "- 다음 쪽은 `pageResponse.nextCursor` 를 `cursor` 로 넘긴다. 최근 300건까지만 내려간다.\n" +
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
}
