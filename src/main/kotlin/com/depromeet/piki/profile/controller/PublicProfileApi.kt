package com.depromeet.piki.profile.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.profile.controller.dto.PublicFolderDetailResponse
import com.depromeet.piki.profile.controller.dto.PublicProfileResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import java.util.UUID

private const val USER_NOT_FOUND = "사용자를 찾을 수 없음 (없는 사용자 · 탈퇴한 사용자 — code: USER-001)"
private const val FOLDER_NOT_VISIBLE =
    "폴더를 볼 수 없음 (없는 폴더 · 지운 폴더 · 비공개 폴더 · 주인이 프로필 비공개 · 차단 관계 — code: USER-001). " +
        "이유를 가르지 않는다 — 가르면 폴더의 존재와 차단 사실이 드러난다"

@Tag(name = "Profile", description = "다른 사람 프로필·공개 폴더 조회 API (비로그인 가능)")
interface PublicProfileApi {
    @Operation(
        summary = "프로필 조회",
        description =
            "사용자 한 명의 프로필과 공개 폴더를 돌려준다. 내 프로필도 같은 API 로 본다(relation=SELF).\n\n" +
                "- **비로그인도 호출할 수 있다.** 토큰이 있으면 관계(친구·차단)가 반영된다.\n" +
                "- 닉네임과 프로필 이미지는 항상 내려간다. 가리는 것은 폴더뿐이다.\n" +
                "- 폴더를 볼 수 없으면 `foldersHidden=true` 와 `folders=[]` 다(프로필 비공개 · 차단 관계). " +
                "공개한 폴더가 없는 사람은 `foldersHidden=false` 라 두 화면을 가를 수 있다.\n" +
                "- 상대가 나를 차단한 경우는 **비공개 프로필과 같은 응답**(`foldersHidden=true`)이라 차단 사실이 드러나지 않는다.\n" +
                "- 폴더 카드의 `coverImageUrls` 는 내 폴더 목록과 같은 규칙이다(최근 넣은 위시 최대 3장, 이미지 없는 칸은 null).\n" +
                "- `relation=BLOCKED` 는 **내가 상대를 차단한** 경우만이다. 상대가 나를 차단한 경우는 " +
                "`NOT_FRIEND` 로 내려가 차단 사실이 드러나지 않는다.\n" +
                "- 친구 수는 내려보내지 않는다. 그 수는 내 친구 목록 화면에만 있다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 (폴더를 볼 수 없으면 foldersHidden=true · folders=[])",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = USER_NOT_FOUND,
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun getProfile(
        @Parameter(hidden = true) viewerId: UUID?,
        @Parameter(description = "볼 프로필의 사용자 id") userId: UUID,
    ): ApiResponseBody<PublicProfileResponse>

    @Operation(
        summary = "공개 폴더 상세",
        description =
            "다른 사람의 공개 폴더 하나와 그 안의 위시를 돌려준다.\n\n" +
                "- 위시 카드 값은 내 위시 목록과 같은 계산이다.\n" +
                "- 그 위시가 주인의 어느 폴더에 들어 있는지는 담지 않는다. 주인의 비공개 폴더 id 가 드러나기 때문이다.\n" +
                "- **볼 수 없으면 이유를 가르지 않고 전부 404 다.**",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 (빈 폴더면 wishes=[])",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = FOLDER_NOT_VISIBLE,
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun getPublicFolder(
        @Parameter(hidden = true) viewerId: UUID?,
        @Parameter(description = "폴더 주인의 사용자 id") userId: UUID,
        @Parameter(description = "볼 폴더 id") folderId: Long,
    ): ApiResponseBody<PublicFolderDetailResponse>
}
