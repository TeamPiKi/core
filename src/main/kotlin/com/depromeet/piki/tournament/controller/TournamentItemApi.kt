package com.depromeet.piki.tournament.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.image.controller.dto.ConfirmImageUploadRequest
import com.depromeet.piki.image.controller.dto.PresignedImageUploadRequest
import com.depromeet.piki.image.controller.dto.PresignedImageUploadResponse
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.tournament.controller.dto.AddTournamentItemFromLinkRequest
import com.depromeet.piki.tournament.controller.dto.AddTournamentItemFromLinkResponse
import com.depromeet.piki.tournament.controller.dto.AddTournamentItemsFromImagesResponse
import com.depromeet.piki.tournament.controller.dto.AddTournamentItemsFromWishResponse
import com.depromeet.piki.tournament.controller.dto.AddTournamentItemsRequest
import com.depromeet.piki.tournament.controller.dto.TournamentItemDetailResponse
import com.depromeet.piki.tournament.controller.dto.UpdateTournamentItemRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import java.util.UUID

// 아이템 등록 한도(#339) 응답 설명. 이 축의 한도는 요청자가 아니라 **토너먼트 오너**의 몫에서 깎이므로,
// 참여자는 자기가 처음 담는 경우에도 429 를 받을 수 있다. 클라이언트가 그 상황을 오해하지 않게 명시한다.
// (응답 문구 자체는 오너의 사용량을 드러내지 않는다 — 남의 사용량은 요청자에게 알릴 정보가 아니다.)
private const val TOURNAMENT_RATE_LIMIT_DESCRIPTION =
    "아이템 등록 한도 초과 (code: ITEM-006). 한도는 요청자가 아니라 토너먼트 오너의 몫에서 차감되므로 " +
        "참여자가 처음 담는 경우에도 받을 수 있다. 그 몫은 토너먼트 전용이 아니라 오너 계정 하나의 몫이라 " +
        "오너의 위시 등록과도 공유된다. 요청 수가 아니라 등록하는 item 수로 세며(이미지 5장 = 5), " +
        "남은 몫이 있으면 그보다 큰 요청도 통과하므로 이 응답은 몫을 이미 다 쓴 뒤부터 나온다. " +
        "Retry-After 헤더에 한도가 풀리기까지 남은 시간(초)이 실린다."

// 전역 가용량 소진(#927) 응답 설명. 429 와 원인이 다르다 — 오너의 몫이 남아 있어도 서비스 전체가 차면 나간다.
private const val CAPACITY_DESCRIPTION =
    "서비스 전체의 시간당 처리 가용량 소진 (code: COMMON-SERVER-BUSY). 오너의 몫이 남아 있어도 나갈 수 있다 — " +
        "서비스가 감당하기로 정한 총량이 찬 상태라 같은 시각 모든 사용자에게 동일하게 나간다. 정상 운영에서는 " +
        "닿지 않는 마지노선이므로 이 응답이 반복되면 서버 쪽 이상 신호다. Retry-After 헤더에 가용량이 회복되기까지 " +
        "남은 시간(초)이 실린다."

@Tag(name = "Tournament Item", description = "토너먼트 아이템 API")
interface TournamentItemApi {

    @Operation(
        summary = "토너먼트 아이템 단건 조회",
        description = """
            토너먼트 아이템의 상세 정보와 파싱 상태를 조회한다.
            링크·이미지로 아이템을 추가하면 비동기 파싱이 진행되므로,
            클라이언트는 SSE(`/api/v1/notifications/subscribe`)로 완료·실패를 통보받고, 이 조회로 최신 status 와 값을 확인한다.
            - PARSING: 상품 확정 전 파싱 중 (itemId·name·price·imageUrl 은 null)
            - READY: 이름·가격·이미지가 모두 있음
            - INCOMPLETE: 일부만 있음 (사용자 입력으로 채우면 READY)
            - FAILED: 상품을 확정하지 못하고 파싱 실패 (상품 페이지 아님 또는 추출 불가)
            sourceUrl: 상품 페이지 링크. 파싱 성공 전에는 등록 때 입력한 링크, 성공 뒤에는 확정된 상품 페이지 주소. 이미지로 등록한 경우 null.
            memo: 이 상품이 **요청자 본인의 위시리스트에 있으면** 그 위시의 개인 메모(최대 100자). 누가 토너먼트에
            추가했는지와 무관하게 본인 위시 기준으로 판정하며, 본인 메모만 내려간다(타인 메모는 어떤 경우에도 없음).
            위시에 없거나 메모를 안 적었거나 게스트면 필드 자체가 생략된다.
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
                description = "권한 없음 (토너먼트 참여자가 아님 · 위시리스트에 없는 아이템 포함 · 옛 클론 토너먼트 id 로 요청)",
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
                description =
                    "상태 충돌 (PENDING이 아닌 토너먼트 · 이미 등록된 아이템 · 요청 내 중복 아이템 · " +
                        "값이 비어 있는 미완성 상품 포함)",
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
            토너먼트 참여자만 추가할 수 있다.
            토너먼트 후보가 파싱 중 카드로 즉시 생성되어 tournamentItemId 가 반환된다. 상품은 파싱 성공이나 사용자 입력으로 확정된다.
            파싱은 비동기로 진행되며, PARSING 에서 READY·INCOMPLETE·FAILED 상태로 전환된다.
            확정된 상품을 같은 토너먼트의 다른 후보가 이미 갖고 있으면 이 후보는 그 후보에 합쳐져 사라지고(ITEM_PARSING_MERGED 알림,
            참여자에게 TOURNAMENT_ITEM_MERGED 신호), 이 tournamentItemId 로 조회하면 404 다.
            클라이언트는 SSE(`/api/v1/notifications/subscribe`)로 파싱 결과를 통보받고, tournamentItemId 로 GET /tournaments/{id}/items/{tournamentItemId} 를 조회해 결과를 확인한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "토너먼트 후보 추가 성공 (파싱은 백그라운드)",
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
                    "잘못된 요청 (URL 미입력 · URL ${ProductLink.MAX_LENGTH}자 초과 — code: COMMON-INVALID-INPUT · " +
                        "URL 형식 오류 — code: LINK-001(유효하지 않음)/LINK-002(https 외 스킴) · " +
                        "지원하지 않는 쇼핑몰 — code: LINK-003 · 아이템 최대 32개 초과)",
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
                description = "권한 없음 (토너먼트 참여자가 아님 · 옛 클론 토너먼트 id 로 요청)",
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
                description =
                    "상태 충돌 (PENDING 이 아닌 토너먼트 · 이미 담긴 링크 - 같은 상품을 다시 추가하면 " +
                        "`data.tournamentItemId` 에 이미 출전 중인 그 아이템의 id 가 실린다)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "429",
                description = TOURNAMENT_RATE_LIMIT_DESCRIPTION,
                headers = [
                    Header(
                        name = "Retry-After",
                        description = "한도가 풀리기까지 남은 시간(초). RFC 9110 delta-seconds.",
                        schema = Schema(type = "integer", format = "int64"),
                    ),
                ],
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "503",
                description = CAPACITY_DESCRIPTION,
                headers = [
                    Header(
                        name = "Retry-After",
                        description = "가용량이 회복되기까지 남은 시간(초). RFC 9110 delta-seconds.",
                        schema = Schema(type = "integer", format = "int64"),
                    ),
                ],
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
        summary = "이미지로 토너먼트 아이템 추가 v2 - presigned 업로드 URL 발급",
        description = """
            이미지 등록의 1단계. 올릴 이미지들의 content-type·바이트 수(1~5개)를 받아, 클라가 S3 에 직접 PUT 할 presigned URL 을 발급한다.
            원본 바이트가 서버를 경유하지 않아 서버 대역·메모리를 쓰지 않는다.
            참여자·PENDING 권한을 사전 검증하며, 정원(최대 32개) 최종 판정은 저장이 일어나는 2단계(/images/confirm)로 미룬다.
            content-type 과 contentLength 는 서명에 묶인다 — 클라는 각 uploadUrl 로 같은 Content-Type·Content-Length 로만 PUT 할 수 있고(S3 가 강제),
            imageKey 들을 confirm 으로 되돌려준다. 한 장의 상한은 5MB 다.
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
                    "잘못된 요청 (이미지 개수 1~5 위반 · content-type 미지정 — code: PRODUCTIMAGE-002 · " +
                        "지원하지 않는 이미지 형식(png/jpeg/webp/heic/heif만 허용) — code: PRODUCTIMAGE-003 · " +
                        "contentLength 5MB 초과 — code: UPLOAD-003 · contentLength 미지정 또는 0 이하 — code: UPLOAD-004)",
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
                description = "권한 없음 (토너먼트 참여자가 아님 · 옛 클론 토너먼트 id 로 요청)",
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
                responseCode = "429",
                description = "$TOURNAMENT_RATE_LIMIT_DESCRIPTION 발급 시점에 차감하므로 이어지는 confirm 은 추가로 소모하지 않는다.",
                headers = [
                    Header(
                        name = "Retry-After",
                        description = "한도가 풀리기까지 남은 시간(초). RFC 9110 delta-seconds.",
                        schema = Schema(type = "integer", format = "int64"),
                    ),
                ],
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "502",
                description = "presigned URL 발급 실패 (스토리지 장애 — 클라이언트는 재시도) — code: STORAGE-002",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "503",
                description = "$CAPACITY_DESCRIPTION 발급 시점에 확인하므로 이어지는 confirm 은 이 응답을 받지 않는다.",
                headers = [
                    Header(
                        name = "Retry-After",
                        description = "가용량이 회복되기까지 남은 시간(초). RFC 9110 delta-seconds.",
                        schema = Schema(type = "integer", format = "int64"),
                    ),
                ],
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
            이미지 등록의 2단계. presigned 로 업로드를 마친 imageKey(1~5개)를 받아, 각 이미지를 파싱 중인 토너먼트 후보로 즉시 추가하고 tournamentItemIds 를 반환한다.
            key 형식·실제 업로드 여부(S3 존재)를 검증한 뒤 링크 추가와 같은 작업 큐에 적재하며, 이후 파싱·전이(READY/INCOMPLETE/FAILED)와 합침 흐름도 같다.
            이 토너먼트에 남아 있는 후보로 이미 확정한 imageKey 는 다시 추가하지 않고 응답에서도 빠진다. 그래서 요청보다 적은 id 나 빈 배열이 올 수 있다.
            클라이언트는 SSE(`/api/v1/notifications/subscribe`)로 파싱 결과를 통보받고, tournamentItemId 로 GET /tournaments/{id}/items/{tournamentItemId} 를 조회해 결과를 확인한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "토너먼트 후보 추가 성공 (파싱은 백그라운드)",
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
                    "잘못된 요청 (imageKey 개수 1~5 위반 · 발급 형식이 아닌 key — code: UPLOAD-001 · " +
                        "아직 업로드되지 않은 이미지 — code: UPLOAD-002 · 아이템 최대 32개 초과)",
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
                description = "권한 없음 (토너먼트 참여자가 아님 · 옛 클론 토너먼트 id 로 요청)",
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
                description = "이미지 존재 확인 실패 (S3 HEAD 중 스토리지 장애 — 클라이언트는 재시도) — code: STORAGE-003",
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
        summary = "토너먼트 아이템 사용자 입력",
        description = """
            토너먼트 아이템의 상품 정보를 등록한 본인이 직접 입력한다(multipart/form-data).
            입력 필드: 이름, 가격, 가격 단위, 이미지(image 파트). 이미지는 파일로 업로드하며 서버가 S3 에 저장한 URL 이 대표 이미지가 된다.
            입력은 기존 버전을 고치지 않고 사용자 입력 새 버전으로 쌓이며, 이 아이템의 표시값이 그 버전으로 바뀐다.

            **보내지 않은 필드는 지금 보이는 값을 이어 쓴다.** 합친 결과에 이름·가격·이미지가 모두 있어야 하고,
            하나라도 빠지면 400 이다. 그래서 아이템 상태에 따라 보내야 하는 필드가 다르다.
            - READY: 바꿀 필드만
            - INCOMPLETE: 바꿀 필드와 비어 있는 필드
            - FAILED: 이름·가격·이미지 전부 (이어 쓸 값이 없다)
            - PARSING: 받지 않는다 (409, code: ITEM-008). 결과가 오기 전에 입력하면 뒤늦게 끝난 파싱과 엇갈린다.
              클라이언트는 이 동안 입력 화면을 막는다고 전제하고, 409 는 그 사이 상태가 바뀐 경합을 막는다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "입력 성공 (사용자 입력 새 버전이 이 아이템의 표시값이 됨)",
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
                    "잘못된 요청 (합친 뒤에도 상품명 없음 — code: ITEM-003 · 합친 뒤에도 가격 없음 — code: ITEM-004 · " +
                        "합친 뒤에도 이미지 없음 — code: ITEM-005 · 이름 1자 미만 512자 초과 · 가격 음수 · 가격단위 8자 초과 · " +
                        "빈 이미지 — code: PRODUCTIMAGE-001 · 이미지 타입 미지정 — code: PRODUCTIMAGE-002 · " +
                        "지원하지 않는 이미지 형식(png/jpeg/webp/heic/heif만 허용) — code: PRODUCTIMAGE-003 · " +
                        "요청 값 형식 검증 실패 — COMMON-INVALID-INPUT)",
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
                description = "권한 없음 (토너먼트 참여자가 아님 · 아이템을 등록한 본인이 아님 · 옛 클론 토너먼트 id 로 요청)",
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
                description =
                    "상태 충돌 (PENDING이 아닌 토너먼트 · 파싱 중인 아이템에 상품 정보 입력 — code: ITEM-008)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "502",
                description = "외부 의존성 실패 (이미지 저장소(S3) 업로드 실패 — 재시도 가능) — code: STORAGE-001",
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
                description = "권한 없음 (아이템을 추가한 본인도 아니고 토너먼트 소유자도 아님 · 옛 클론 토너먼트 id 로 요청)",
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
