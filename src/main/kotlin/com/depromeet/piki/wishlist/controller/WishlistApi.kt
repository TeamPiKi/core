package com.depromeet.piki.wishlist.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.image.controller.dto.ConfirmImageUploadRequest
import com.depromeet.piki.image.controller.dto.PresignedImageUploadRequest
import com.depromeet.piki.image.controller.dto.PresignedImageUploadResponse
import com.depromeet.piki.metrics.registration.ExternalEntry
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.wishlist.controller.dto.WishDetailResponse
import com.depromeet.piki.wishlist.controller.dto.WishItemResponse
import com.depromeet.piki.wishlist.controller.dto.WishlistRegisterRequest
import com.depromeet.piki.wishlist.controller.dto.WishlistUpdateRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

private const val RATE_LIMIT_DESCRIPTION =
    "아이템 등록 한도 초과 (code: ITEM-006). 몫은 위시 전용이 아니라 계정 하나의 몫이라, 이 사용자가 소유한 " +
        "토너먼트에 아이템이 추가된 양(참여 게스트가 넣은 것 포함)도 같은 몫에서 빠진다. 한도는 요청 수가 아니라 " +
        "등록하는 item 수로 센다 — 이미지 5장 등록은 5 를 소모하고, 새로고침도 파싱이 다시 돌므로 1 을 소모한다. " +
        "남은 몫이 있으면 그보다 큰 요청도 통과하므로(마지막 한 번은 성공) 이 응답은 몫을 이미 다 쓴 뒤부터 나온다. " +
        "Retry-After 헤더에 한도가 풀리기까지 남은 시간(초)이 실린다."

private const val CAPACITY_DESCRIPTION =
    "서비스 전체의 시간당 처리 가용량 소진 (code: COMMON-SERVER-BUSY). 요청자가 자기 몫을 다 쓴 429 와 달리, " +
        "서비스가 감당하기로 정한 총량이 찬 상태라 같은 시각 모든 사용자에게 동일하게 나간다. 정상 운영에서는 " +
        "닿지 않는 마지노선이므로 이 응답이 반복되면 서버 쪽 이상 신호다. Retry-After 헤더에 가용량이 회복되기까지 " +
        "남은 시간(초)이 실린다."

@Tag(name = "Wishlist", description = "위시리스트 등록/조회/복구/삭제 API")
interface WishlistApi {
    @Operation(
        summary = "위시리스트 등록 (URL)",
        description = """
            상품 페이지 URL 을 받아 위시리스트에 등록한다. 메타데이터(이름/가격/이미지) 추출은
            오래 걸리므로 동기로 기다리지 않는다. 등록 즉시 item.status=PARSING(item.id 는 null) 인 항목을 201 로 반환하고,
            백그라운드 파싱이 성공하면 상품이 확정돼 READY 또는 INCOMPLETE, 실패하면 FAILED 가 된다.
            확정된 상품을 내 다른 위시가 이미 갖고 있으면 이 위시는 그 위시에 합쳐져 사라지고(메모는 옮겨진다) ITEM_PARSING_MERGED 알림이 온다.
            클라이언트는 SSE(`/api/v1/notifications/subscribe`)로 알림을 받고 위시리스트를 재조회해 확인한다. URL 형식 오류는 등록 전에 400 으로 거른다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "위시리스트 등록 접수 (item.status=PARSING, item.id=null, 파싱은 백그라운드)",
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
                    "잘못된 요청 (URL 이 비어 있음 · ${ProductLink.MAX_LENGTH}자 초과 — code: COMMON-INVALID-INPUT · " +
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
                description =
                    "이미 위시리스트에 등록된 상품 (같은 상품을 다시 담음 — code: WISH-009, " +
                        "`data.wishId` 에 이미 담긴 그 위시의 id 가 실린다. 확정된 내 위시의 상품과 주소가 같거나, " +
                        "같은 링크로 담은 내 위시가 있으면 파싱 중·실패 상태여도 해당한다) · " +
                        "탈퇴한 계정 (JWT 는 아직 유효하나 계정이 탈퇴 상태 — code: USER-003)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "429",
                description = RATE_LIMIT_DESCRIPTION,
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
    fun registerFromUrl(
        @Parameter(hidden = true) userId: UUID,
        request: WishlistRegisterRequest,
        @Parameter(
            `in` = ParameterIn.HEADER,
            name = ExternalEntry.HEADER,
            required = false,
            description = "외부에서 넘어온 담기일 때만 보낸다. SHARE_SHEET 는 타앱 공유 시트로 링크가 전달된 경우다. " +
                "앱이나 웹에서 직접 담는 경우는 기본이라 보내지 않는다. 집계 전용이라 등록 결과는 달라지지 않고, " +
                "모르는 값을 보내도 400 이 아니라 집계에서 빠질 뿐이다.",
            schema = Schema(type = "string", allowableValues = ["SHARE_SHEET"]),
        )
        rawEntryPoint: String?,
    ): ApiResponseBody<WishItemResponse>

    @Operation(
        summary = "위시리스트 조회 (다건)",
        description = """
            로그인한 유저 본인의 위시리스트를 최신 등록순(id desc)으로 조회한다.
            cursor 페이지네이션: 직전 응답의 pageResponse.nextCursor 를 다음 요청 cursor 로 그대로 전달한다.
            마지막 페이지면 nextCursor 는 null, hasNext 는 false.
            size 는 미지정 시 20, 1~50 범위를 벗어나면 양 끝으로 보정된다.
            각 항목의 item.status 로 상태(PARSING/FAILED/READY/INCOMPLETE)를 구분한다 —
            PARSING 인 항목은 SSE(`/api/v1/notifications/subscribe`)로 결과를 통보받고 이 조회로 확인한다.
            같은 상품의 다른 위시에 합쳐진 항목은 목록에서 사라진다(ITEM_PARSING_MERGED).
            item.refreshing=true 면 새로고침 중이고, 값과 status 는 새로고침 전 것이다.
            INCOMPLETE 는 추출이 일부 필드만 채운 상태다 — 빈 필드를 사용자 입력(PATCH)으로 채우면 READY 가 된다.
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
            wishId 로 위시 항목 하나를 상세 조회한다. 화면에 표시할 값(item)과 가격 이력(priceHistory)을 함께 내려주므로
            상세 화면은 이 API 하나로 그린다. 본인 위시만 조회할 수 있다.

            - item: 화면에 그대로 표시하는 값. 목록 조회의 item 과 같은 규칙으로 파생돼 두 화면에서 같게 보인다.
              값이 비어 있으면 status 로 갈린다 (PARSING 은 대기, FAILED 는 사용자 입력 유도이며 새로고침이 거부된다).
              상태 전이 통보는 SSE(`/api/v1/notifications/subscribe`)로 받는다.
            - priceHistory: 이 상품의 가격 기록을 최신순 최대 50건. 서버 추출값과 사용자 입력값이 함께 들어가고
              userInput·editedByMe 로 구분한다.
            - **지금 보이는 값은 항상 item 에서 읽는다.** priceHistory 는 표시값 선택 규칙을 타지 않아 첫 항목이 item 과 다를 수 있다.
            - memo: 본인만 보는 개인 메모(최대 100자). 같은 상품을 담은 다른 사용자와 공유되지 않으며,
              상세 응답에만 내려간다(목록 응답에는 없다). 수정은 사용자 입력 API(PATCH)의 memo 필드로 한다.
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
    ): ApiResponseBody<WishDetailResponse>

    @Operation(
        summary = "위시 항목 사용자 입력",
        description = """
            위시 항목의 상품 정보를 사용자가 직접 입력한다(multipart/form-data).
            텍스트(이름·현재가·통화)는 form 필드로, 이미지는 image 파트로 받는다 — 이미지는 URL 이 아니라 파일로만 받아
            서버가 가공 없이 그대로 S3 에 올려 대표 이미지로 쓴다.
            입력은 기존 버전을 고치지 않고 **사용자 입력 새 버전**으로 쌓이며, 이 위시의 표시값이 그 버전으로 바뀐다.
            본인 위시만 수정할 수 있다.

            **응답의 wish.id 가 요청한 wishId 와 다를 수 있다.** FAILED 위시에 입력해 정해진 상품을 내 다른 위시가 이미 갖고 있으면,
            요청한 위시는 그 위시에 합쳐져 사라지고(메모는 옮겨진다) 응답에는 남은 위시가 실린다. 클라이언트는 요청한 카드를 지우고
            응답의 wish.id 로 화면을 갱신한다.

            **보내지 않은 필드는 지금 보이는 값을 이어 쓴다.** 합친 결과에 이름·가격·이미지가 모두 있어야 하고,
            하나라도 빠지면 400 이다. 그래서 item.status 에 따라 보내야 하는 필드가 다르다.
            - READY: 바꿀 필드만
            - INCOMPLETE: 바꿀 필드와 비어 있는 필드
            - FAILED: 이름·가격·이미지 전부 (이어 쓸 값이 없다)
            - PARSING 이거나 item.refreshing=true: 받지 않는다 (409, code: ITEM-008). 결과가 오기 전에 입력하면
              뒤늦게 끝난 파싱과 엇갈린다. 클라이언트는 이 동안 입력 화면을 막는다고 전제하고, 409 는 그 사이 상태가 바뀐 경합을 막는다.

            **memo(개인 메모, 최대 100자)** 도 이 API 로 수정한다 — 미전송(null)이면 미변경, 빈 문자열이면 삭제.
            메모는 상품 정보가 아니라 위시 개인 필드라, **memo 만 보내면 상품의 새 버전을 만들지 않고**
            메모만 바뀐다(이때는 필수값 검증도, 파싱 중 거부도 없다). 메모는 상세 조회 응답에서만 확인한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "입력 성공 — 사용자 입력 새 버전이 이 위시의 표시값이 됨 (memo 만 보낸 경우 버전은 그대로). 다른 위시에 합쳐지면 남은 위시를 반환",
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
                        "합친 뒤에도 이미지 없음 — code: ITEM-005 · price 음수 · name/currency/memo 길이 초과 · " +
                        "빈 이미지 — code: PRODUCTIMAGE-001 · 이미지 Content-Type 없음 — code: PRODUCTIMAGE-002 · " +
                        "지원하지 않는 이미지 형식(png/jpeg/webp/heic/heif만 허용) — code: PRODUCTIMAGE-003)",
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
                description = "파싱 중이거나 새로고침 중인 항목에 상품 정보 입력 — code: ITEM-008 · 탈퇴한 계정 — code: USER-003",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "502",
                description = "이미지 저장소(S3) 업로드 실패 — 재시도 가능 — code: STORAGE-001",
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
            위시 항목의 상품 정보를 원본 링크로 다시 추출해 최신(가격·이미지 등)으로 새로고침한다. 추출은 오래 걸려
            동기로 기다리지 않는다 — item.refreshing=true 로 200 을 반환하고, 그동안 값과 status 는 새로고침 전 것을 유지한다.
            클라이언트는 SSE(`/api/v1/notifications/subscribe`)로 결과를 통보받는데, 등록과 달리
            완료·실패는 `ITEM_REFRESH_COMPLETED` / `ITEM_REFRESH_FAILED` 로 온다(#1036). 일부 필드만 채워진 경우는 등록과 같은 `ITEM_PARSING_INCOMPLETE` 다.
            이미 새로고침이 진행 중이거나 등록 파싱이 아직 끝나지 않은 항목(PARSING)이면 새 추출을 만들지 않고 현재 상태를 그대로 반환한다(멱등).
            새로고침은 상품이 확정된 항목(READY·INCOMPLETE)의 재추출 전용이다. 추출에 실패(FAILED)한 항목은 새로고침 대신 사용자 입력(PATCH)으로 복구한다(409).
            이미지로 등록해 상품이 확정된 항목은 재추출할 링크가 없어 새로고침할 수 없다(400). 본인 위시만 가능하다.
            이 상품을 토너먼트에 출전시켜 둔 경우 시작 전 출전 카드에는 새 값이 보이고, 시작한 토너먼트의 출전 값은 시작 시각 기준이라 바뀌지 않는다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "새로고침 접수 (item.refreshing=true, 파싱은 백그라운드) — 이미 진행 중이거나 등록 파싱 중(PARSING)이면 현재 상태를 그대로 반환(멱등)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "이미지로 등록해 상품이 확정된 항목은 재추출할 링크가 없어 새로고침할 수 없음 (code: ITEM-007). 실패한 이미지 항목은 409",
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
                description = "추출 실패(FAILED) 항목은 새로고침 대상이 아님 (사용자 입력으로 복구) · 새로고침은 READY·INCOMPLETE 항목 전용 · 탈퇴한 계정(code: USER-003)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "429",
                description = RATE_LIMIT_DESCRIPTION,
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
        summary = "위시리스트 이미지 등록 v2 - presigned 업로드 URL 발급",
        description = """
            이미지 등록의 1단계. 올릴 이미지들의 content-type·바이트 수(1~5개)를 받아, 클라가 S3 에 직접 PUT 할 presigned URL 을 발급한다.
            원본 바이트가 서버를 경유하지 않아 서버 대역·메모리를 쓰지 않는다.
            content-type 과 contentLength 는 서명에 묶인다 — 클라는 각 uploadUrl 로 같은 Content-Type·Content-Length 로만 PUT 할 수 있고(S3 가 강제),
            imageKey 들을 2단계(/images/confirm)로 되돌려준다. 한 장의 상한은 5MB 다.
            발급 시점에는 위시를 만들지 않는다. 위시는 확정 단계에서 생기고, 상품은 그 뒤 파싱 성공이나 사용자 입력으로 정해진다.
            확정이 오지 않은 업로드는 등록되지 않는다.
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
                responseCode = "429",
                description = "$RATE_LIMIT_DESCRIPTION 발급 시점에 차감하므로 이어지는 confirm 은 추가로 소모하지 않는다.",
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
        request: PresignedImageUploadRequest,
    ): ApiResponseBody<PresignedImageUploadResponse>

    @Operation(
        summary = "위시리스트 이미지 등록 v2 - 업로드 확정",
        description = """
            이미지 등록의 2단계. presigned 로 업로드를 마친 imageKey(1~5개)를 받아, 각 이미지를 PARSING 위시로 즉시 등록하고 목록을 반환한다.
            key 형식·실제 업로드 여부(S3 존재)를 검증한 뒤 URL 등록과 같은 작업 큐에 적재하며, 이후 추출·전이(READY/INCOMPLETE/FAILED) 흐름도 같다.
            결과 모양(WishItemResponse)은 URL 등록과 동일하다 — 다만 URL 이 없어 sourceUrl 이 null 이며,
            추출 결과는 SSE(`/api/v1/notifications/subscribe`)로 통보받아 재조회하고, 추출 실패(FAILED) 항목은 사용자 입력 API(PATCH)로 복구한다.
            남아 있는 위시로 이미 확정한 imageKey 는 다시 등록하지 않고 응답에서도 빠진다. 그래서 요청보다 적은 항목이나 빈 목록이 올 수 있다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "이미지 등록 접수 — 각 항목이 PARSING 상태로 생성되고 비동기 파싱이 시작된다",
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
                        "아직 업로드되지 않은 이미지 — code: UPLOAD-002)",
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
        request: ConfirmImageUploadRequest,
    ): ApiResponseBody<List<WishItemResponse>>
}
