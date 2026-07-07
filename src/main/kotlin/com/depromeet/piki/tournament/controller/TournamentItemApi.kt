package com.depromeet.piki.tournament.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.image.controller.dto.ConfirmImageUploadRequest
import com.depromeet.piki.image.controller.dto.PresignedImageUploadRequest
import com.depromeet.piki.image.controller.dto.PresignedImageUploadResponse
import com.depromeet.piki.tournament.controller.dto.AddTournamentItemFromLinkRequest
import com.depromeet.piki.tournament.controller.dto.AddTournamentItemFromLinkResponse
import com.depromeet.piki.tournament.controller.dto.AddTournamentItemsFromImagesResponse
import com.depromeet.piki.tournament.controller.dto.AddTournamentItemsFromWishResponse
import com.depromeet.piki.tournament.controller.dto.AddTournamentItemsRequest
import com.depromeet.piki.tournament.controller.dto.TournamentItemDetailResponse
import com.depromeet.piki.tournament.controller.dto.UpdateTournamentItemRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@Tag(name = "Tournament Item", description = "토너먼트 아이템 API")
interface TournamentItemApi {

    @Operation(
        summary = "토너먼트 아이템 단건 조회",
        description = """
            토너먼트 아이템의 상세 정보와 파싱 상태를 조회한다.
            링크·이미지로 아이템을 추가하면 비동기 파싱이 진행되므로,
            클라이언트는 SSE(`/api/v1/notifications/subscribe`)로 PENDING → PROCESSING → READY/FAILED 전환(완료·실패 알림)을 통보받고, 이 조회로 최신 status 와 값을 확인한다.
            - PENDING: URL·이미지 등록 접수 후 파싱 대기 (디스패처가 집기 전, name·price·imageUrl 은 null).
            - PROCESSING: 파싱 진행 중 (name·price·imageUrl 은 null)
            - READY: 파싱 완료 (모든 필드 채워짐)
            - FAILED: 파싱 실패 (상품 페이지 아님 또는 추출 불가)
            sourceUrl: 등록 시 입력한 원본 링크. 이미지로 등록한 경우 null.
            토너먼트 참여자만 조회할 수 있다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "아이템 조회 성공",
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
                responseCode = "403",
                description = "권한 없음 (토너먼트 참여자가 아님)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "토너먼트를 찾을 수 없음 · 토너먼트 아이템을 찾을 수 없음 · 아이템이 해당 토너먼트에 속하지 않음",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun getTournamentItem(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "토너먼트 ID", example = "1") tournamentId: Long,
        @Parameter(description = "토너먼트 아이템 ID", example = "10") tournamentItemId: Long,
    ): ApiResponseBody<TournamentItemDetailResponse>

    @Operation(
        summary = "위시에서 토너먼트 아이템 추가",
        description = """
            PENDING 상태의 토너먼트에 위시리스트에 있는 아이템을 추가한다. 토너먼트 참여자(회원)만 호출 가능.
            플레이 링크로 생성된 복제 토너먼트에는 추가 불가.
            itemIds 중 하나라도 조건에 맞지 않으면 요청 전체가 실패한다(부분 성공 없음).
            응답의 tournamentItemIds 는 요청 itemIds 와 동일한 순서로 대응된다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "아이템 추가 성공 (tournamentItemIds: 요청 itemIds 순서와 동일하게 대응)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청 (itemIds 1~32개 범위 초과 · 아이템 최대 32개 초과)",
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
                responseCode = "403",
                description = "권한 없음 (토너먼트 참여자가 아님 · 위시리스트에 없는 아이템 포함 · 플레이 링크로 생성된 복제 토너먼트)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "토너먼트를 찾을 수 없음 · 존재하지 않는 아이템 포함",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "상태 충돌 (PENDING이 아닌 토너먼트 · 이미 등록된 아이템 · 요청 내 중복 아이템 · PENDING/PROCESSING/FAILED 등 미완료 상품 포함)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun addItemsFromWish(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "토너먼트 ID", example = "1") tournamentId: Long,
        request: AddTournamentItemsRequest,
    ): ApiResponseBody<AddTournamentItemsFromWishResponse>

    @Operation(
        summary = "URL 링크로 토너먼트 아이템 추가",
        description = """
            PENDING 상태의 토너먼트에 URL 링크를 통해 아이템을 추가한다.
            플레이 링크로 생성된 복제 토너먼트에는 추가 불가. 토너먼트 참여자만 추가할 수 있다.
            아이템이 PENDING 상태로 즉시 생성되어 tournamentItemId 가 반환된다.
            파싱은 비동기로 진행되며, 디스패처가 PENDING 을 집어 PROCESSING 으로 전이한 뒤 READY 또는 FAILED 상태로 전환된다.
            클라이언트는 SSE(`/api/v1/notifications/subscribe`)로 파싱 완료·실패를 통보받고, tournamentItemId 로 GET /tournaments/{id}/items/{tournamentItemId} 를 조회해 결과를 확인한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "아이템 추가 성공 (item.status=PENDING, 파싱은 백그라운드)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청 (URL 미입력 · URL 형식 오류(비어 있음/유효하지 않음/https 외 스킴) · URL 2048자 초과 · 지원하지 않는 쇼핑몰 · 아이템 최대 32개 초과)",
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
                responseCode = "403",
                description = "권한 없음 (토너먼트 참여자가 아님 · 플레이 링크로 생성된 복제 토너먼트)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "토너먼트를 찾을 수 없음",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "상태 충돌 (PENDING이 아닌 토너먼트)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun addItemFromLink(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "토너먼트 ID", example = "1") tournamentId: Long,
        request: AddTournamentItemFromLinkRequest,
    ): ApiResponseBody<AddTournamentItemFromLinkResponse>

    @Operation(
        summary = "이미지로 토너먼트 아이템 추가",
        description = """
            PENDING 상태의 토너먼트에 이미지 추출을 통해 아이템을 추가한다.
            플레이 링크로 생성된 복제 토너먼트에는 추가 불가. 토너먼트 참여자만 추가할 수 있다.
            이미지 1~5장을 전달하면 아이템이 PENDING 상태로 즉시 생성되어(link 처럼 outbox 적재) tournamentItemIds 가 반환된다.
            이미지 파싱은 비동기로 진행되며 완료 시 READY 또는 FAILED 상태로 전환된다.
            클라이언트는 SSE(`/api/v1/notifications/subscribe`)로 파싱 완료·실패를 통보받고, tournamentItemId 로 GET /tournaments/{id}/items/{tournamentItemId} 를 조회해 결과를 확인한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "아이템 추가 성공 (item.status=PENDING, 파싱은 백그라운드)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청 (이미지 1~5장 범위 초과 · 빈 이미지 · 이미지 타입 미지정 · 지원하지 않는 이미지 형식(png/jpeg/webp/heic/heif만 허용) · 아이템 최대 32개 초과)",
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
                responseCode = "403",
                description = "권한 없음 (토너먼트 참여자가 아님 · 플레이 링크로 생성된 복제 토너먼트)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "토너먼트를 찾을 수 없음",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "상태 충돌 (PENDING이 아닌 토너먼트)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "502",
                description = "이미지 저장 실패 (원본을 S3 에 적재하는 중 스토리지 장애 — 클라이언트는 재시도)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun addItemsFromImages(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "토너먼트 ID", example = "1") tournamentId: Long,
        images: List<MultipartFile>?,
    ): ApiResponseBody<AddTournamentItemsFromImagesResponse>

    @Operation(
        summary = "이미지로 토너먼트 아이템 추가 v2 - presigned 업로드 URL 발급",
        description = """
            이미지 등록 v2 의 1단계. 올릴 이미지들의 content-type(1~5개)을 받아, 클라가 S3 에 직접 PUT 할 presigned URL 을 발급한다.
            v1(multipart)이 서버로 바이트를 받아 S3 에 올리던 것을 클라→S3 직접 업로드로 바꿔 서버 대역·메모리를 아낀다.
            참여자·PENDING·비복제 권한을 사전 검증하며, 정원(최대 32개) 최종 판정은 저장이 일어나는 2단계(/images/confirm)로 미룬다.
            클라는 각 uploadUrl 로 응답의 contentType 을 Content-Type 헤더에 실어 PUT 한 뒤, imageKey 들을 confirm 으로 되돌려준다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "발급 성공 — 각 이미지의 uploadUrl·imageKey·contentType 반환",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description =
                    "잘못된 요청 (content-type 개수 1~5 위반 · content-type 미지정 · " +
                        "지원하지 않는 이미지 형식(png/jpeg/webp/heic/heif만 허용))",
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
                responseCode = "403",
                description = "권한 없음 (토너먼트 참여자가 아님 · 플레이 링크로 생성된 복제 토너먼트)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "토너먼트를 찾을 수 없음",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "상태 충돌 (PENDING이 아닌 토너먼트)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "502",
                description = "presigned URL 발급 실패 (스토리지 장애 — 클라이언트는 재시도)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun presignImageUploads(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "토너먼트 ID", example = "1") tournamentId: Long,
        request: PresignedImageUploadRequest,
    ): ApiResponseBody<PresignedImageUploadResponse>

    @Operation(
        summary = "이미지로 토너먼트 아이템 추가 v2 - 업로드 확정",
        description = """
            이미지 등록 v2 의 2단계. presigned 로 업로드를 마친 imageKey(1~5개)를 받아, 각 이미지를 PENDING 아이템으로 즉시 추가하고 tournamentItemIds 를 반환한다.
            key 형식·실제 업로드 여부(S3 존재)를 검증한 뒤 v1 과 같은 outbox 에 적재하며, 이후 파싱(Gemini Vision)·전이(READY/FAILED) 흐름은 v1 과 완전히 같다.
            클라이언트는 SSE(`/api/v1/notifications/subscribe`)로 파싱 완료·실패를 통보받고, tournamentItemId 로 GET /tournaments/{id}/items/{tournamentItemId} 를 조회해 결과를 확인한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "아이템 추가 성공 (item.status=PENDING, 파싱은 백그라운드)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description =
                    "잘못된 요청 (imageKey 개수 1~5 위반 · 발급 형식이 아닌 key · 아직 업로드되지 않은 이미지 · 아이템 최대 32개 초과)",
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
                responseCode = "403",
                description = "권한 없음 (토너먼트 참여자가 아님 · 플레이 링크로 생성된 복제 토너먼트)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "토너먼트를 찾을 수 없음",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "상태 충돌 (PENDING이 아닌 토너먼트)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "502",
                description = "이미지 존재 확인 실패 (S3 HEAD 중 스토리지 장애 — 클라이언트는 재시도)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun confirmImageRegistration(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "토너먼트 ID", example = "1") tournamentId: Long,
        request: ConfirmImageUploadRequest,
    ): ApiResponseBody<AddTournamentItemsFromImagesResponse>

    @Operation(
        summary = "토너먼트 아이템 수정",
        description = """
            파싱 실패(FAILED) 상태인 토너먼트 아이템을 유저가 직접 보정한다.
            수정 성공 시 아이템 상태가 FAILED → READY 로 전환된다.
            수정 가능 필드: 이름, 가격, 가격 단위, 이미지(multipart/form-data 의 image 파트) — null 이면 기존 값 유지.
            이미지는 파일로 업로드하며 서버가 S3 에 저장한 URL 로 item.imageUrl 을 갱신한다.
            READY·PENDING·PROCESSING 아이템은 수정 불가(409). 아이템을 등록한 본인만 수정 가능.
            이름은 수정 후에도 반드시 존재해야 한다 — 기존 이름이 없고 name 도 미입력이면 400.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "수정 성공 (FAILED → READY 전환)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청 (이름 없이 수정 시도 · 이름 1자 미만 512자 초과 · 가격 음수 · 가격단위 8자 초과 · 빈 이미지 · 이미지 타입 미지정 · 지원하지 않는 이미지 형식(png/jpeg/webp/heic/heif만 허용))",
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
                responseCode = "403",
                description = "권한 없음 (토너먼트 참여자가 아님 · 아이템을 등록한 본인이 아님)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "토너먼트를 찾을 수 없음 · 토너먼트 아이템을 찾을 수 없음",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "상태 충돌 (PENDING이 아닌 토너먼트 · READY·PENDING·PROCESSING 상태 아이템)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "502",
                description = "외부 의존성 실패 (이미지 저장소(S3) 업로드 실패 — 재시도 가능)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun updateItem(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "토너먼트 ID", example = "1") tournamentId: Long,
        @Parameter(description = "토너먼트 아이템 ID", example = "10") tournamentItemId: Long,
        request: UpdateTournamentItemRequest,
    ): ApiResponseBody<Unit>

    @Operation(
        summary = "토너먼트 아이템 삭제",
        description = "PENDING 상태의 토너먼트에서 아이템을 제거한다. 아이템을 추가한 본인 또는 토너먼트 소유자만 삭제할 수 있다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "아이템 삭제 성공",
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
                responseCode = "403",
                description = "권한 없음 (아이템을 추가한 본인도 아니고 토너먼트 소유자도 아님)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "토너먼트를 찾을 수 없음 · 토너먼트 아이템을 찾을 수 없음",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "상태 충돌 (PENDING이 아닌 토너먼트)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun deleteItem(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "토너먼트 ID", example = "1") tournamentId: Long,
        @Parameter(description = "토너먼트 아이템 ID", example = "10") tournamentItemId: Long,
    ): ApiResponseBody<Unit>
}
