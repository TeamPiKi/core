package com.depromeet.piki.folder.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.folder.controller.dto.FolderMoveRequest
import com.depromeet.piki.folder.controller.dto.FolderMoveResponse
import com.depromeet.piki.folder.controller.dto.FolderNameRequest
import com.depromeet.piki.folder.controller.dto.FolderResponse
import com.depromeet.piki.folder.controller.dto.FolderWishCountResponse
import com.depromeet.piki.folder.controller.dto.FolderWishesRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import java.util.UUID

private const val UNAUTHORIZED = "미인증 (JWT 토큰 없음 또는 유효하지 않음)"
private const val GUEST = "권한 없음 (GUEST 권한으로 접근 불가 · MEMBER 필요 — code: WISH-001)"
private const val DELETED_USER = "탈퇴한 계정 (JWT 는 아직 유효하나 계정이 탈퇴 상태 — code: USER-003)"
private const val NOT_FOUND = "폴더 없음 (없는 폴더 · 지운 폴더 · 남의 폴더 — code: FOLDER-001)"
private const val INVALID_NAME = "잘못된 이름 (앞뒤 공백을 뺀 이름이 비었거나 20자 초과. 이모지·결합 문자는 1자로 센다 — code: FOLDER-002)"
private const val INVALID_WISH_COUNT = "wishIds 가 비었거나 100개 초과 (중복은 하나로 센다 — code: FOLDER-003)"

@Tag(name = "Folder", description = "위시 폴더 만들기·조회·이름 바꾸기·지우기, 폴더에 위시 넣기·빼기·옮기기 API")
interface FolderApi {
    @Operation(
        summary = "폴더 만들기",
        description = "빈 폴더를 만든다. 같은 이름의 폴더를 여러 개 만들 수 있다. 위시리스트처럼 회원 전용이다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "생성 (wishCount=0, coverImageUrls=[])", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(responseCode = "400", description = INVALID_NAME, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(responseCode = "401", description = UNAUTHORIZED, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(responseCode = "403", description = GUEST, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(responseCode = "409", description = DELETED_USER, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
        ],
    )
    fun create(
        userId: UUID,
        request: FolderNameRequest,
    ): ApiResponseBody<FolderResponse>

    @Operation(
        summary = "폴더 목록",
        description = """
            내 폴더 전부를 생성순(먼저 만든 폴더가 앞)으로 돌려준다. 사람이 손으로 만드는 수라 페이지 없이 한 번에 준다.
            wishCount 는 폴더 안 위시 수이고 지운 위시는 세지 않는다. coverImageUrls 는 최근 넣은 위시부터
            이미지가 있는 것만 골라 최대 3장이다(파싱 중·실패 위시는 이미지가 없어 건너뛴다).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 (폴더가 없으면 빈 배열)", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(responseCode = "401", description = UNAUTHORIZED, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(responseCode = "403", description = GUEST, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(responseCode = "409", description = DELETED_USER, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
        ],
    )
    fun getFolders(userId: UUID): ApiResponseBody<List<FolderResponse>>

    @Operation(
        summary = "폴더 이름 바꾸기",
        description = "바꿀 수 있는 것은 이름뿐이다. 이름 규칙은 폴더 만들기와 같다. 지금 이름과 같은 이름으로 바꿔도 성공한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "변경 성공 (바뀐 폴더)", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(responseCode = "400", description = INVALID_NAME, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(responseCode = "401", description = UNAUTHORIZED, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(responseCode = "403", description = GUEST, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(responseCode = "404", description = NOT_FOUND, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(responseCode = "409", description = DELETED_USER, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
        ],
    )
    fun rename(
        userId: UUID,
        folderId: Long,
        request: FolderNameRequest,
    ): ApiResponseBody<FolderResponse>

    @Operation(
        summary = "폴더 지우기",
        description = "폴더와 그 안의 연결만 지운다. 폴더에 있던 위시는 전체 위시에 그대로 남는다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "삭제 성공 (data=null)", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(responseCode = "401", description = UNAUTHORIZED, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(responseCode = "403", description = GUEST, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(responseCode = "404", description = NOT_FOUND, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(responseCode = "409", description = DELETED_USER, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
        ],
    )
    fun delete(
        userId: UUID,
        folderId: Long,
    ): ApiResponseBody<Unit>

    @Operation(
        summary = "폴더에 위시 넣기",
        description = """
            한 번에 100개까지 넣는다. 이미 들어 있는 위시는 에러 없이 건너뛴다. 남의 위시나 지운 위시가 하나라도 섞여 있으면
            요청 전체를 거절한다. 응답의 wishCount 는 넣은 뒤 폴더 안 위시 수다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "넣기 성공", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(responseCode = "400", description = INVALID_WISH_COUNT, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(responseCode = "401", description = UNAUTHORIZED, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(
                responseCode = "403",
                description = "권한 없음 (GUEST — code: WISH-001 · 남의 위시나 지운 위시 포함 — code: WISH-002)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(responseCode = "404", description = NOT_FOUND, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(responseCode = "409", description = DELETED_USER, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
        ],
    )
    fun putWishes(
        userId: UUID,
        folderId: Long,
        request: FolderWishesRequest,
    ): ApiResponseBody<FolderWishCountResponse>

    @Operation(
        summary = "폴더에서 위시 빼기",
        description = """
            넣기와 대칭이다. wishIds 는 DELETE body 를 버리는 게이트웨이가 있어 query 로 받는다(?wishIds=101&wishIds=102).
            들어 있지 않은 위시는 건너뛴다. 위시 자체는 지워지지 않고 전체 위시와 다른 폴더에 남는다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "빼기 성공", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(responseCode = "400", description = INVALID_WISH_COUNT, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(responseCode = "401", description = UNAUTHORIZED, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(responseCode = "403", description = GUEST, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(responseCode = "404", description = NOT_FOUND, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(responseCode = "409", description = DELETED_USER, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
        ],
    )
    fun takeOutWishes(
        userId: UUID,
        folderId: Long,
        wishIds: List<Long>?,
    ): ApiResponseBody<FolderWishCountResponse>

    @Operation(
        summary = "다른 폴더로 위시 옮기기",
        description = """
            이 폴더에서 빼고 대상 폴더에 넣는 것을 한 번에 처리한다. 빼기와 넣기를 따로 부르면 중간에 끊길 때
            위시가 두 폴더에 다 있거나 어디에도 없는 상태가 남는다. 이 폴더에 들어 있는 위시만 옮기고,
            대상 폴더에 이미 있는 위시는 이 폴더에서 빼기만 한다. 넣기와 같이 남의 위시나 지운 위시가 하나라도 섞여 있으면
            요청 전체를 거절한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "이동 성공 (두 폴더의 새 위시 수)", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청 (wishIds 가 비었거나 100개 초과 — code: FOLDER-003 · 이 폴더와 대상 폴더가 같음 — code: FOLDER-004)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(responseCode = "401", description = UNAUTHORIZED, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
            ApiResponse(
                responseCode = "403",
                description = "권한 없음 (GUEST — code: WISH-001 · 남의 위시나 지운 위시 포함 — code: WISH-002)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "폴더 없음 (이 폴더나 대상 폴더가 없음 · 지움 · 남의 폴더 · targetFolderId 누락 — code: FOLDER-001)",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))],
            ),
            ApiResponse(responseCode = "409", description = DELETED_USER, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiResponseBody::class))]),
        ],
    )
    fun moveWishes(
        userId: UUID,
        folderId: Long,
        request: FolderMoveRequest,
    ): ApiResponseBody<FolderMoveResponse>
}
