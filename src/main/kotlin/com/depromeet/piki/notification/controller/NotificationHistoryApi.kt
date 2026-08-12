package com.depromeet.piki.notification.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.notification.controller.dto.NotificationDeleteRequest
import com.depromeet.piki.notification.controller.dto.NotificationDeleteResponse
import com.depromeet.piki.notification.controller.dto.NotificationHistoryResponse
import com.depromeet.piki.notification.controller.dto.NotificationReadRequest
import com.depromeet.piki.notification.controller.dto.NotificationReadResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import java.util.UUID

@Tag(name = "Notification", description = "알림 API")
interface NotificationHistoryApi {
    @Operation(
        summary = "알림 히스토리 조회",
        description =
            "로그인한 유저 본인의 알림을 **최신순(`id` desc)** 으로 조회한다 (**GUEST·MEMBER 모두**).\n\n" +
                "**커서 페이지네이션**\n\n" +
                "- 직전 응답의 `pageResponse.nextCursor` 를 다음 요청 `cursor` 로 그대로 전달한다.\n" +
                "- 마지막 페이지면 `nextCursor` 는 `null`, `hasNext` 는 `false`.\n" +
                "- `size` 는 미지정 시 20, 1~50 범위를 벗어나면 양 끝으로 보정된다.\n\n" +
                "**응답 활용**\n\n" +
                "- 응답 `data` 의 `unreadCount`(앱 badge)로 안읽음 수를 함께 내려준다 (별도 카운트 API 없음). " +
                "페이지 범위가 아니라 항상 본인 전체 안읽음 수다.\n" +
                "- 각 항목 셰입은 SSE `notification` 이벤트 payload 와 동일하다 — `type` 으로 화면을, `kind` 로 라벨·아이콘(위시/토너먼트/시스템)과 딥링크 출처를, " +
                "`refId` 로 이동 대상을 정하고, `id` 로 단건 읽음 처리(`POST /read`)를 한다.\n\n" +
                "**알림 타입 카탈로그 (전 10종)**\n\n" +
                "`type` 으로 화면을 분기하고 `refId` 로 이동 대상을 정한다. `kind` 는 **전 알림 공통 필드**로 항상 실리며 카드 라벨·아이콘(위시/토너먼트/시스템)이 된다. " +
                "`body` 는 **`ITEM_PARSING_COMPLETED` 와 `ANNOUNCEMENT` 만 값이 있고 나머지 타입은 빈 문자열(`\"\"`)** 이다. " +
                "전자는 `title` 에 아이템 이름을, `body` 에 상태 문구를 나눠 싣는다 — OS 푸시 제목은 줄바꿈 없이 뒤가 잘려서, " +
                "이름과 상태를 한 줄에 담으면 이름이 길 때 상태가 사라지기 때문이다. 후자는 관리자가 입력한 공지 본문이 그대로 `body` 에 실린다. " +
                "클라이언트는 `body` 가 비어 있을 수 있음을 전제로 그린다.\n\n" +
                "| `type` | 트리거 | `kind` | `refId` | `title` 예시 |\n" +
                "|---|---|---|---|---|\n" +
                "| `TOURNAMENT_JOINED` | 토너먼트 참가 | `TOURNAMENT` | tournamentId | {참가자}님이 참가했어요 |\n" +
                "| `TOURNAMENT_ITEM_ADDED` | 아이템 추가 | `TOURNAMENT` | tournamentId | {참가자}님이 아이템을 추가했어요 |\n" +
                "| `TOURNAMENT_ITEM_DELETED` | 아이템 삭제 | `TOURNAMENT` | tournamentId | {참가자}님이 '{상품명}'을(를) 삭제했어요 |\n" +
                "| `TOURNAMENT_STARTED` | 토너먼트 시작 | `TOURNAMENT` | tournamentId | {주최자}님이 토너먼트를 시작했어요 |\n" +
                "| `TOURNAMENT_PLAYED_FROM_LINK` | 플레이링크로 플레이 시작 | `TOURNAMENT` | ROOT 토너먼트 id | {플레이어}님이 회원님 토너먼트를 플레이했어요 |\n" +
                "| `TOURNAMENT_COMPLETED` | 멤버가 클론 완료 | `TOURNAMENT` | ROOT 토너먼트 id | {멤버}님이 회원님 토너먼트를 완료했어요 |\n" +
                "| `TOURNAMENT_RESULT_READY` | 주최자가 ROOT 완료 | `TOURNAMENT` | ROOT 토너먼트 id | 참여하신 {주최자}님의 토너먼트 결과가 나왔어요 |\n" +
                "| `ITEM_PARSING_COMPLETED` | 상품 추출 성공 | 출처에 따라 `WISH` 또는 `TOURNAMENT` | itemId | {아이템 이름} (+ `body` = 파싱이 완료되었어요) |\n" +
                "| `ITEM_PARSING_FAILED` | 상품 추출 실패 | 출처에 따라 `WISH` 또는 `TOURNAMENT` | itemId | 상품 정보를 가져오지 못했어요 |\n" +
                "| `ANNOUNCEMENT` | 관리자 공지(후속) | `SYSTEM` | 공지 id/0 | (관리자 입력) |\n\n" +
                "> `ITEM_PARSING_COMPLETED` 의 `body` 는 `kind` 와 무관하게 하나다 — 위시로 담았든 토너먼트로 올렸든 같은 문구가 온다. " +
                "화면 분기는 `kind` 로 하고 `body` 문구에 기대지 않는다.\n\n" +
                "> 파싱 알림(`ITEM_PARSING_*`)만 `kind` 가 발행 출처(위시 등록 / 토너먼트 추가)에 따라 갈린다 — 같은 `type` 이 두 플로우에서 발행되기 때문. " +
                "나머지 타입은 위 표의 값 하나로 고정이다.\n\n" +
                "> 아이템 좌표(`tournamentId`·`tournamentItemId`)가 추가로 실리는 타입: 토너먼트 출처 파싱 알림(`ITEM_PARSING_*` + `kind`=TOURNAMENT)과 " +
                "아이템 삭제(`TOURNAMENT_ITEM_DELETED`). 후자는 클라가 재조회 없이 그 항목만 제거하게 한다. 나머지 타입엔 그 키가 없다. " +
                "**좌표 유무는 `kind` 가 아니라 `type` 이 가른다** — 토너먼트 소셜 알림도 `kind`=TOURNAMENT 지만 좌표가 없으므로, `kind` 만 보고 좌표를 단정하지 말 것. " +
                "`title` 은 발송 시점 렌더 값이라 클라는 문구가 아니라 `type` 으로 분기한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공 (목록 + unreadCount)",
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
                    "잘못된 요청\n\n" +
                        "- cursor 가 숫자로 변환되지 않음 (`NOTIFICATION-001`)\n" +
                        "- size 가 정수로 변환되지 않음 (숫자 아닌 값 · Int 범위 초과 → 바인딩 실패 → `COMMON-INVALID-INPUT`). " +
                        "정수이기만 하면 1~50 밖이어도 400 이 아니라 양 끝으로 보정된다.",
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
        ],
    )
    fun getHistory(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "직전 응답의 nextCursor (없으면 첫 페이지)", example = "1010")
        cursor: String?,
        @Parameter(description = "페이지 크기 (기본 20, 최대 50)", example = "20")
        size: Int?,
    ): ApiResponseBody<NotificationHistoryResponse>

    @Operation(
        summary = "알림 읽음 처리",
        description =
            "알림을 읽음 처리한다 (**GUEST·MEMBER 모두, 본인 알림만**). 요청 body 는 두 방식 중 **정확히 하나**:\n\n" +
                "| 방식 | 동작 |\n" +
                "|---|---|\n" +
                "| `all=true` | 본인 안읽음 알림 전부 읽음 (전체 읽음 버튼, 화면 이동 없음) |\n" +
                "| `ids=[...]` | 지정한 알림만 읽음 (단건 클릭은 `[id]` 1개, 클릭 후 FE 가 딥링크로 이동) |\n\n" +
                "- 둘 다 보내거나 둘 다 비우면(빈 `ids` 포함) **400**.\n" +
                "- `ids` 는 본인 소유만 반영되고 타인·없는 id 는 무시된다. **멱등**(이미 읽음도 성공).\n" +
                "- 응답 `data` 의 `unreadCount`(앱 badge)로 처리 후 안읽음 수를 **서버 권위 값**으로 내려준다 — " +
                "클라는 이 값을 그대로 badge 로 미러링한다(별도 카운트 조회 불필요).",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "읽음 처리 성공 (처리 후 unreadCount 동봉, 멱등)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청 (all 과 ids 를 함께 보냄 · 둘 다 없음 · 빈 ids)",
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
        ],
    )
    fun read(
        @Parameter(hidden = true) userId: UUID,
        request: NotificationReadRequest,
    ): ApiResponseBody<NotificationReadResponse>

    @Operation(
        summary = "알림 삭제",
        description =
            "알림을 삭제한다 (**GUEST·MEMBER 모두, 본인 알림만**). 하드삭제라 삭제된 알림은 히스토리에서 영구 제거된다(복구 없음). " +
                "요청 body 는 읽음(`POST /read`)과 같은 계약을 미러링해 두 방식 중 **정확히 하나**:\n\n" +
                "| 방식 | 동작 |\n" +
                "|---|---|\n" +
                "| `all=true` | 본인 알림 전부 삭제 (읽음 무관, 모두 삭제 버튼) |\n" +
                "| `ids=[...]` | 지정한 알림만 삭제 (단건은 `[id]` 1개, 다건은 `[id, ...]`) |\n\n" +
                "- 둘 다 보내거나 둘 다 비우면(빈 `ids` 포함) **400**.\n" +
                "- `ids` 는 본인 소유만 삭제되고 타인·없는 id 는 무시된다. **멱등**(이미 없는 것도 성공).\n" +
                "- 삭제로 안읽음 알림이 사라지면 badge 도 줄어든다. 응답 `data` 의 `unreadCount`(앱 badge)로 " +
                "삭제 후 안읽음 수를 **서버 권위 값**으로 내려준다 — " +
                "클라는 이 값을 그대로 badge 로 미러링한다(읽음 응답과 동일 셰입).",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "삭제 성공 (삭제 후 unreadCount 동봉, 멱등)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청 (all 과 ids 를 함께 보냄 · 둘 다 없음 · 빈 ids)",
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
        ],
    )
    fun delete(
        @Parameter(hidden = true) userId: UUID,
        request: NotificationDeleteRequest,
    ): ApiResponseBody<NotificationDeleteResponse>
}
