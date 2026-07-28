package com.depromeet.piki.wishlist.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.image.controller.dto.ConfirmImageUploadRequest
import com.depromeet.piki.image.controller.dto.PresignedImageUploadRequest
import com.depromeet.piki.image.controller.dto.PresignedImageUploadResponse
import com.depromeet.piki.wishlist.controller.dto.WishItemResponse
import com.depromeet.piki.wishlist.controller.dto.WishPriceHistoryResponse
import com.depromeet.piki.wishlist.controller.dto.WishlistRegisterRequest
import com.depromeet.piki.wishlist.controller.dto.WishlistUpdateRequest
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

@Tag(name = "Wishlist", description = "위시리스트 등록/조회/복구/삭제 API")
interface WishlistApi {
    @Operation(
        summary = "위시리스트 등록 (URL)",
        description = """
            상품 페이지 URL 을 받아 위시리스트에 등록한다. 메타데이터(이름/가격/이미지) 추출은 외부 LLM 호출이라
            오래 걸리므로 동기로 기다리지 않는다. 등록 즉시 item.status=PENDING 인 항목을 201 로 반환하고,
            실제 파싱은 백그라운드 디스패처가 PENDING 을 집어 PROCESSING 으로 전이한 뒤 READY(완료) 또는 FAILED(파싱 실패) 로 전이한다.
            클라이언트는 SSE(`/api/v1/notifications/subscribe`)로 status 변화(PENDING→PROCESSING→READY/FAILED, 완료·실패 알림)를 통보받고 위시리스트를 재조회해 확인한다. URL 형식 오류는 등록 전에 400 으로 거른다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "위시리스트 등록 접수 (item.status=PENDING, 파싱은 백그라운드)",
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
                    "잘못된 요청 (URL 이 비어 있음 · 2048자 초과 — code: COMMON-INVALID-INPUT · " +
                        "유효한 URL 형식이 아님 — code: LINK-001 · https 외 스킴 — code: LINK-002 · " +
                        "지원하지 않는 쇼핑몰 — code: LINK-003)",
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
                description = "권한 없음 (GUEST 권한으로 접근 불가 · MEMBER 필요)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "탈퇴한 계정 (JWT 는 아직 유효하나 계정이 탈퇴 상태) — code: USER-003",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun registerFromUrl(
        @Parameter(hidden = true) userId: UUID,
        request: WishlistRegisterRequest,
    ): ApiResponseBody<WishItemResponse>

    @Operation(
        summary = "위시리스트 조회 (다건)",
        description = """
            로그인한 유저 본인의 위시리스트를 최신 등록순(id desc)으로 조회한다.
            cursor 페이지네이션: 직전 응답의 pageResponse.nextCursor 를 다음 요청 cursor 로 그대로 전달한다.
            마지막 페이지면 nextCursor 는 null, hasNext 는 false.
            size 는 미지정 시 20, 1~50 범위를 벗어나면 양 끝으로 보정된다.
            각 항목의 item.status 로 파싱 상태(PENDING/PROCESSING/READY/FAILED)를 구분한다 —
            등록 직후 PENDING·PROCESSING 인 항목은 SSE(`/api/v1/notifications/subscribe`)로 READY/FAILED 전이를 통보받고 이 조회로 확인한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "위시리스트 조회 성공",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "유효하지 않은 cursor 값 (숫자로 변환 불가)",
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
                description = "권한 없음 (GUEST 권한으로 접근 불가 · MEMBER 필요)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "탈퇴한 계정 (JWT 는 아직 유효하나 계정이 탈퇴 상태) — code: USER-003",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun getWishlist(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "직전 응답의 nextCursor (없으면 첫 페이지)", example = "1010")
        cursor: String?,
        @Parameter(description = "페이지 크기 (기본 20, 최대 50)", example = "20")
        size: Int?,
    ): ApiResponseBody<List<WishItemResponse>>

    @Operation(
        summary = "위시리스트 조회 (단건)",
        description = """
            wishId 로 위시 항목 하나를 조회한다. 응답 모양은 목록 조회 항목과 같은 WishItemResponse(wish + item).
            본인 위시만 조회 가능하며, item 을 직접 노출하지 않고 위시 소유 단위로 권한을 검증한다.
            item.status 로 파싱 상태(PENDING/PROCESSING/READY/FAILED)를 구분한다. 상세 화면 진입 시 현재 status 확인에 쓰고, 전이 통보는 SSE(`/api/v1/notifications/subscribe`)로 받는다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
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
                description = "권한 없음 (GUEST 권한으로 접근 불가 · MEMBER 필요, 또는 본인 위시가 아님)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "존재하지 않는 위시 항목 (삭제된 항목 포함)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "탈퇴한 계정 (JWT 는 아직 유효하나 계정이 탈퇴 상태) — code: USER-003",
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
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "위시 항목 ID", example = "1024") wishId: Long,
    ): ApiResponseBody<WishItemResponse>

    @Operation(
        summary = "위시 상품 가격 히스토리 조회",
        description = """
            wishId 로 그 위시가 가리키는 상품의 가격 히스토리를 조회한다. 갱신·새로고침마다 새 추출 버전이 쌓여
            가격·이름·이미지 이력이 보존되며, 이 API 는 그중 추출 완료(READY)된 버전을 최신순(snapshotId desc)으로 내려준다.
            가격이 없는 PENDING·PROCESSING·FAILED 버전은 entries 에서 제외된다 — 비어 있으면 아직 한 번도 추출에 성공하지 않은 상품이다.
            entries 각 항목은 그 버전 시점의 가격·이름·이미지·추출시각을 담고, isActive 로 현재 활성(위시가 가리키는) 버전을 표시한다.
            응답의 activeSnapshotId 는 현재 활성 버전 식별자다 — 활성 버전이 추출 중이거나 실패면 entries 에 없을 수 있다.
            본인 위시만 조회 가능하며, item 을 직접 노출하지 않고 위시 소유 단위로 권한을 검증한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "가격 히스토리 조회 성공 (READY 버전 최신순, 없으면 빈 entries)",
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
                description = "권한 없음 (GUEST 권한으로 접근 불가 · MEMBER 필요, 또는 본인 위시가 아님)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "존재하지 않는 위시 항목 (삭제된 항목 포함)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "탈퇴한 계정 (JWT 는 아직 유효하나 계정이 탈퇴 상태) — code: USER-003",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun getPriceHistory(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "위시 항목 ID", example = "1024") wishId: Long,
    ): ApiResponseBody<WishPriceHistoryResponse>

    @Operation(
        summary = "위시 항목 복구 (추출 실패 보정)",
        description = """
            추출에 실패(item.status=FAILED)한 위시 항목의 상품 정보를 사용자가 직접 채워 복구한다(multipart/form-data).
            텍스트(이름·현재가·통화)는 form 필드로, 이미지는 image 파트로 받는다 — 이미지는 URL 이 아니라 파일로만 받아
            서버가 그대로 S3 에 올려 대표 이미지로 채운다(추출·크롭 없음). 들어온 값만 갱신하고, 보정에 성공하면 READY 로 복구된다.
            item 데이터는 링크에서 기계 추출한 사실이라, 이미 완성(READY)된 항목은 수정할 수 없고(409 CONFLICT),
            대기·파싱 중(PENDING·PROCESSING)인 항목은 백그라운드 워커 소관이라 끼어들 수 없다(409 CONFLICT).
            본인 위시만 보정 가능하며, item 을 직접 노출하지 않고 위시 소유 단위로 권한을 검증한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "추출 실패(FAILED) 항목 보정 성공 — status 가 READY 로 복구됨",
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
                    "잘못된 요청 (보정 후에도 상품명 없음 — code: ITEM-003 · 가격 없이 READY 전환 — code: ITEM-004 · " +
                        "이미지 없이 READY 전환 — code: ITEM-005 · currentPrice 음수 · name/currency 길이 초과 · " +
                        "빈 이미지 · 지원하지 않는 이미지 형식(png/jpeg/webp/heic/heif만 허용))",
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
                description = "권한 없음 (GUEST 권한으로 접근 불가 · MEMBER 필요, 또는 본인 위시가 아님)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "존재하지 않는 위시 항목 (삭제된 항목 포함)",
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
                    "수정할 수 없는 상태 (이미 등록 완료(READY) — code: ITEM-001 · " +
                        "아직 대기·처리 중(PENDING·PROCESSING) — code: ITEM-002) · 탈퇴한 계정(code: USER-003)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "502",
                description = "이미지 저장소(S3) 업로드 실패 — 재시도 가능",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun recoverWishItem(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "위시 항목 ID", example = "1024") wishId: Long,
        request: WishlistUpdateRequest,
        image: MultipartFile?,
    ): ApiResponseBody<WishItemResponse>

    @Operation(
        summary = "위시 항목 새로고침 (링크 재추출)",
        description = """
            위시 항목의 상품 정보를 원본 링크로 다시 추출해 최신(가격·이미지 등)으로 새로고침한다. 추출은 외부 LLM 호출이라
            동기로 기다리지 않는다 — 새 추출 버전(item.status=PENDING)을 즉시 활성으로 띄워 200 으로 반환하고, 백그라운드 디스패처가
            집어 PROCESSING→READY(완료)/FAILED(실패) 로 전이한다. 클라이언트는 등록과 동일하게 SSE(`/api/v1/notifications/subscribe`)로 status 변화(완료·실패 알림)를 통보받는다.
            이미 새로고침이 진행 중(PENDING·PROCESSING)이면 새 추출을 만들지 않고 현재 진행 상태를 그대로 반환한다(멱등).
            새로고침은 성공(READY) 항목의 재추출 전용이다. 추출에 실패(FAILED)한 항목은 새로고침 대신 보정으로 복구한다(409).
            링크가 없는 항목(이미지로 등록한 위시)은 재추출 입력이 없어 새로고침할 수 없다(400). 본인 위시만 가능하다.
            옛 추출 버전은 보존돼, 이 위시를 토너먼트에 출전시켜 둔 경우 출전 시점 정보가 새로고침에 영향받지 않는다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "새로고침 접수 (새 추출 버전 item.status=PENDING, 파싱은 백그라운드) — 이미 진행 중이면 현재 진행 상태를 그대로 반환(멱등)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "링크가 없는 항목(이미지로 등록한 위시)은 재추출 입력이 없어 새로고침할 수 없음",
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
                description = "권한 없음 (GUEST 권한으로 접근 불가 · MEMBER 필요, 또는 본인 위시가 아님)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "존재하지 않는 위시 항목 (삭제된 항목 포함)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "추출 실패(FAILED) 항목은 새로고침 대상이 아님 (보정으로 복구) · 새로고침은 성공(READY) 항목 전용 · 탈퇴한 계정(code: USER-003)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun refreshWishItem(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "위시 항목 ID", example = "1024") wishId: Long,
    ): ApiResponseBody<WishItemResponse>

    @Operation(
        summary = "위시리스트 삭제 (단건)",
        description = """
            위시 항목을 삭제한다(soft delete). 멱등 — 이미 없거나 삭제된 항목이면 아무 일도 하지 않고 성공한다.
            존재하는 항목은 본인 위시만 삭제 가능하다. 삭제된 항목은 조회 결과에서 제외된다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "삭제 성공 (없거나 이미 삭제된 항목도 멱등 성공, data 없음)",
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
                description = "권한 없음 (GUEST 권한으로 접근 불가 · MEMBER 필요, 또는 본인 위시가 아님)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "탈퇴한 계정 (JWT 는 아직 유효하나 계정이 탈퇴 상태) — code: USER-003",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun deleteWish(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "위시 항목 ID", example = "1024") wishId: Long,
    ): ApiResponseBody<Unit>

    @Operation(
        summary = "위시리스트 삭제 (다건)",
        description = """
            위시 항목 여러 개를 한 번에 멱등 삭제한다(soft delete). 요청 목록 중 없거나 이미 삭제된 id 는
            무시하고(목표 상태 달성) 성공으로 처리한다. 단 존재하는 항목 중 본인 소유가 아닌 위시가 하나라도
            섞이면 소유권 경계로 403 을 주고 아무것도 삭제하지 않는다. 중복 ID 는 무시한다.
            삭제된 항목은 조회 결과에서 제외된다.
            id 목록은 query param 으로 받는다(예: ?ids=1024,1025,1026, 1~100개). DELETE + body 는
            중간자(게이트웨이·LB·CDN)가 body 를 스트립/거절할 수 있어 피한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "삭제 성공 (없거나 이미 삭제된 항목은 무시, data 없음)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청 (ids 가 비어 있음 · 누락 · 100개 초과)",
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
                description = "권한 없음 (GUEST 권한으로 접근 불가 · MEMBER 필요, 또는 목록에 본인 위시가 아닌 항목이 섞여 있음)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "탈퇴한 계정 (JWT 는 아직 유효하나 계정이 탈퇴 상태) — code: USER-003",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun deleteWishes(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "삭제할 위시 ID 목록 (쉼표 구분, 1~100개)", example = "1024,1025,1026")
        ids: List<Long>?,
    ): ApiResponseBody<Unit>

    @Operation(
        summary = "위시리스트 등록 (이미지)",
        description = """
            상품 페이지를 캡처한 이미지 1~5장을 받아, 각 이미지를 PENDING 상태의 위시 항목으로 즉시 등록하고(link 처럼 outbox 적재) 목록을 반환한다.
            실제 상품 정보 추출(Gemini Vision)은 백그라운드에서 비동기로 진행되어 각 항목을 READY 또는 FAILED 로 전이시킨다.
            URL 등록과 결과 모양(WishItemResponse)이 같다. 이미지 등록 항목은 URL 이 없어 sourceUrl 이 null 이며,
            추출 결과는 SSE(`/api/v1/notifications/subscribe`)로 완료·실패를 통보받아 재조회하며, 추출 실패(FAILED) 항목은 보정 API(PATCH)로 직접 채워 복구한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "이미지 등록 접수 — 각 항목이 PENDING 상태로 생성되고 비동기 파싱이 시작된다",
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
                    "잘못된 요청 (이미지 개수 1~5 위반 · 빈 이미지 · 이미지 타입 미지정 · " +
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
                description = "권한 없음 (GUEST 권한으로 접근 불가 · MEMBER 필요)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "탈퇴한 계정 (JWT 는 아직 유효하나 계정이 탈퇴 상태) — code: USER-003",
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
    fun registerFromImages(
        @Parameter(hidden = true) userId: UUID,
        images: List<MultipartFile>?,
    ): ApiResponseBody<List<WishItemResponse>>

    @Operation(
        summary = "위시리스트 이미지 등록 v2 - presigned 업로드 URL 발급",
        description = """
            이미지 등록 v2 의 1단계. 올릴 이미지들의 content-type(1~5개)을 받아, 클라가 S3 에 직접 PUT 할 presigned URL 을 발급한다.
            v1(multipart)이 서버로 이미지 바이트를 받아 S3 에 올리던 것을 클라→S3 직접 업로드로 바꿔 서버 대역·메모리를 아낀다.
            클라는 각 uploadUrl 로 응답의 contentType 을 Content-Type 헤더에 실어 PUT 한 뒤, imageKey 들을 2단계(/images/confirm)로 되돌려준다.
            발급 시점에는 pending_uploads 에 발급 기록만 남기고 Wish·Item 은 아직 만들지 않는다(확정 단계에서 생성).
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
                description = "권한 없음 (GUEST 권한으로 접근 불가 · MEMBER 필요)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "탈퇴한 계정 (JWT 는 아직 유효하나 계정이 탈퇴 상태) — code: USER-003",
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
        request: PresignedImageUploadRequest,
    ): ApiResponseBody<PresignedImageUploadResponse>

    @Operation(
        summary = "위시리스트 이미지 등록 v2 - 업로드 확정",
        description = """
            이미지 등록 v2 의 2단계. presigned 로 업로드를 마친 imageKey(1~5개)를 받아, 각 이미지를 PENDING 위시로 즉시 등록하고 목록을 반환한다.
            key 형식·실제 업로드 여부(S3 존재)를 검증한 뒤 v1 과 같은 outbox 에 적재하며, 이후 추출(Gemini Vision)·전이(READY/FAILED) 흐름은 v1 과 완전히 같다.
            결과 모양(WishItemResponse)은 v1 이미지 등록과 동일하다 — URL 이 없어 sourceUrl 이 null 이며,
            추출 결과는 SSE(`/api/v1/notifications/subscribe`)로 통보받아 재조회하고, 추출 실패(FAILED) 항목은 보정 API(PATCH)로 복구한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "이미지 등록 접수 — 각 항목이 PENDING 상태로 생성되고 비동기 파싱이 시작된다",
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
                    "잘못된 요청 (imageKey 개수 1~5 위반 · 발급 형식이 아닌 key · 아직 업로드되지 않은 이미지)",
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
                description = "권한 없음 (GUEST 권한으로 접근 불가 · MEMBER 필요)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "탈퇴한 계정 (JWT 는 아직 유효하나 계정이 탈퇴 상태) — code: USER-003",
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
        request: ConfirmImageUploadRequest,
    ): ApiResponseBody<List<WishItemResponse>>
}
