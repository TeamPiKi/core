package com.depromeet.piki.wishlist.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.image.controller.dto.ConfirmImageUploadRequest
import com.depromeet.piki.image.controller.dto.PresignedImageUploadRequest
import com.depromeet.piki.image.controller.dto.PresignedImageUploadResponse
import com.depromeet.piki.wishlist.controller.dto.WishDetailResponse
import com.depromeet.piki.wishlist.controller.dto.WishItemResponse
import com.depromeet.piki.wishlist.controller.dto.WishlistRegisterRequest
import com.depromeet.piki.wishlist.controller.dto.WishlistUpdateRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

// 아이템 등록 한도(#339) 응답 설명. 위시 등록 계열 4개 엔드포인트가 같은 문구를 쓰므로 한 곳에 둔다.
// 세는 단위가 요청 수가 아니라 item 수라는 점, 그리고 몫이 위시 전용이 아니라 계정 전체 몫이라는 점이
// 클라이언트 계약의 핵심이라 명시한다.
private const val RATE_LIMIT_DESCRIPTION =
    "아이템 등록 한도 초과 (code: WISH-010). 몫은 위시 전용이 아니라 계정 하나의 몫이라, 이 사용자가 소유한 " +
        "토너먼트에 아이템이 추가된 양(참여 게스트가 넣은 것 포함)도 같은 몫에서 빠진다. 한도는 요청 수가 아니라 " +
        "등록하는 item 수로 센다 — 이미지 5장 등록은 5 를 소모하고, 새로고침도 파싱이 다시 돌므로 1 을 소모한다. " +
        "남은 몫이 있으면 그보다 큰 요청도 통과하므로(마지막 한 번은 성공) 이 응답은 몫을 이미 다 쓴 뒤부터 나온다. " +
        "Retry-After 헤더에 한도가 풀리기까지 남은 시간(초)이 실린다."

// 전역 가용량 소진(#927) 응답 설명. 429 와 원인이 다르다 — 요청자가 자기 몫을 다 쓴 것이 아니라 서비스가 꽉 찼다.
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
                description = "위시리스트 등록 완료 — 세 모양이 있다. " +
                    "(1) 새 파싱 접수: item.status=PENDING, reused=false. 파싱은 백그라운드. " +
                    "(2) 진행 중 파싱 합류: 같은 상품의 파싱이 이미 돌고 있어 그 결과를 함께 기다린다. " +
                    "item.status=PENDING 또는 PROCESSING, reused=false. " +
                    "(3) 기존 값 재사용: 다른 등록이 만든 완성 값(캐시)에 파싱 없이 붙어 즉시 item.status=READY, reused=true. " +
                    "그 값이 낡았으면(서버 기준 24시간) refreshNeeded=true — 클라가 \"새로운 정보로 가져올까요?\" 를 물어 " +
                    "사용자가 원할 때 새로고침 API 로 재추출한다",
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
                description =
                    "이미 위시리스트에 등록된 상품 (같은 상품을 다시 담음 — code: WISH-009, " +
                        "`data.wishId` 에 이미 담긴 그 위시의 id 가 실린다) · " +
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
    ): ApiResponseBody<WishItemResponse>

    @Operation(
        summary = "위시리스트 조회 (다건)",
        description = """
            로그인한 유저 본인의 위시리스트를 최신 등록순(id desc)으로 조회한다.
            cursor 페이지네이션: 직전 응답의 pageResponse.nextCursor 를 다음 요청 cursor 로 그대로 전달한다.
            마지막 페이지면 nextCursor 는 null, hasNext 는 false.
            size 는 미지정 시 20, 1~50 범위를 벗어나면 양 끝으로 보정된다.
            각 항목의 item.status 로 파싱 상태(PENDING/PROCESSING/READY/INCOMPLETE/FAILED)를 구분한다 —
            등록 직후 PENDING·PROCESSING 인 항목은 SSE(`/api/v1/notifications/subscribe`)로 READY/INCOMPLETE/FAILED 전이를 통보받고 이 조회로 확인한다.
            INCOMPLETE 는 추출이 일부 필드만 채운 상태다 — 빈 필드를 수기 수정(PATCH)으로 채우면 READY 가 된다.
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
              값이 비어 있으면 status 로 갈린다 (PENDING·PROCESSING 은 대기, FAILED 는 수기 입력 유도이며 새로고침이 거부된다).
              상태 전이 통보는 SSE(`/api/v1/notifications/subscribe`)로 받는다.
            - priceHistory: 이 상품의 가격 기록을 최신순 최대 50건. 서버 추출값과 사용자 입력값이 함께 들어가고
              source·editedByMe 로 구분한다.
            - **지금 보이는 값은 항상 item 에서 읽는다.** priceHistory 는 표시값 선택 규칙을 타지 않아 첫 항목이 item 과 다를 수 있다.
            - memo: 본인만 보는 개인 메모(최대 100자). 같은 상품을 담은 다른 사용자와 공유되지 않으며,
              상세 응답에만 내려간다(목록 응답에는 없다). 수정은 수기 수정 API(PATCH)의 memo 필드로 한다.
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
        summary = "위시 항목 수기 수정",
        description = """
            위시 항목의 상품 정보를 사용자가 직접 수정한다(multipart/form-data). 상태 제한이 없다 —
            추출 실패(FAILED) 복구뿐 아니라 완료(READY)·진행 중(PENDING·PROCESSING) 항목도 언제든 수정할 수 있다.
            텍스트(이름·현재가·통화)는 form 필드로, 이미지는 image 파트로 받는다 — 이미지는 URL 이 아니라 파일로만 받아
            서버가 그대로 S3 에 올려 대표 이미지로 쓴다(추출·크롭 없음).
            수정은 기존 버전을 고치지 않고 **수기(MANUAL) 새 버전**으로 쌓인다 — 들어온 값은 현재 버전 값 위에 병합되고,
            이 위시의 표시가 그 버전으로 바뀐다. 진행 중이던 파싱은 계속돼 완료 시 이력으로 남는다.
            본인 위시만 수정 가능하며, item 을 직접 노출하지 않고 위시 소유 단위로 권한을 검증한다.

            **memo(개인 메모, 최대 100자)** 도 이 API 로 수정한다 — 미전송(null)이면 미변경, 빈 문자열이면 삭제.
            메모는 상품 정보와 달리 버전으로 쌓이지 않는 위시 개인 필드라, **memo 만 보내면 새 버전을 만들지 않고**
            메모만 바뀐다(이때 병합 필수값 검증도 없다). 메모는 상세 조회 응답에서만 확인한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "수기 수정 성공 — 수기(MANUAL) 새 버전이 이 위시의 표시 버전이 됨 (memo 만 수정한 경우 버전은 그대로)",
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
                        "이미지 없이 READY 전환 — code: ITEM-005 · price 음수 · name/currency/memo 길이 초과 · " +
                        "빈 이미지 — code: PRODUCTIMAGE-001 · " +
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
                description = "탈퇴한 계정 (code: USER-003)",
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
        summary = "위시리스트 등록 (이미지)",
        description = """
            상품 페이지를 캡처한 이미지 1~5장을 받아, 각 이미지를 PENDING 상태의 위시 항목으로 즉시 등록하고(link 처럼 작업 큐 적재) 목록을 반환한다.
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
                    "잘못된 요청 (이미지 개수 1~5 위반 · 빈 이미지 — code: PRODUCTIMAGE-001 · " +
                        "이미지 타입 미지정 — code: PRODUCTIMAGE-002 · " +
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
                responseCode = "502",
                description = "이미지 저장 실패 (원본을 S3 에 적재하는 중 스토리지 장애 — 클라이언트는 재시도) — code: STORAGE-001",
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
                    "잘못된 요청 (content-type 개수 1~5 위반 · content-type 미지정 — code: PRODUCTIMAGE-002 · " +
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
            이미지 등록 v2 의 2단계. presigned 로 업로드를 마친 imageKey(1~5개)를 받아, 각 이미지를 PENDING 위시로 즉시 등록하고 목록을 반환한다.
            key 형식·실제 업로드 여부(S3 존재)를 검증한 뒤 v1 과 같은 작업 큐에 적재하며, 이후 추출(Gemini Vision)·전이(READY/FAILED) 흐름은 v1 과 완전히 같다.
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
