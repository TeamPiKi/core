package com.depromeet.piki.notification.controller

import com.depromeet.piki.common.response.ApiResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

@Tag(name = "Notification", description = "알림 API")
interface NotificationSseApi {
    @Operation(
        summary = "알림 실시간 구독 (SSE)",
        description =
            "인증 유저의 알림을 실시간으로 받는 **SSE(Server-Sent Events)** 스트림을 연다.\n\n" +
                "응답은 `ApiResponseBody` JSON 래퍼가 아니라 `text/event-stream` 스트림이며, 다음 이벤트가 흘러온다.\n\n" +
                "| 이벤트 | 시점 | 내용 |\n" +
                "|---|---|---|\n" +
                "| `connect` | 구독 직후 1회 | `data=\"connected\"`. 연결 성립 신호 |\n" +
                "| `notification` | 알림 1건마다 | `type` 으로 화면을, 파싱 알림은 `kind` 로 출처(위시/토너먼트)를 분기. " +
                "SSE `id` 필드에 알림 id 가 실린다(재연결 복구 키). " +
                "출처별 payload 셰입과 라우팅 필드(`kind`·`tournamentId`·`tournamentItemId`)는 `notification-sse-spec.md` 참조 |\n" +
                "| `silent-sync` | 화면 갱신 사건마다 | 조용한 화면 갱신 신호(알림 아님). payload 의 `type` 으로 사건을 분기한다: " +
                "`TOURNAMENT_ITEM_PARSED`(`{type, tournamentId, tournamentItemId, status}`, status=`READY`\\|`FAILED`) · " +
                "`UNREAD_COUNT_CHANGED`(`{type, unreadCount, unreadCountByCategory}`, 읽음 후 멀티 디바이스 인앱 배지 동기화). 알림센터·푸시 없이 SSE 로만 흐른다. `notification-sse-spec.md` 참조 |\n" +
                "| `(주석 ping)` | 약 30초 간격 | 하트비트. 연결 유지용이며 data 이벤트가 아니다 |\n\n" +
                "- 토너먼트 알림은 해당 토너먼트 참여자에게만 fan-out 되므로, **자기 스트림 1개만 구독**하면 토너먼트·개인 알림이 모두 도착한다.\n" +
                "- 연결은 **30분 후 타임아웃**되며, 클라이언트는 끊기면 재연결한다.\n" +
                "- **재연결 시 `Last-Event-ID` 헤더**(마지막으로 받은 `notification` 의 SSE `id`)를 보내면 끊김 동안 쌓인 " +
                "`notification` 을 발생 순서대로 다시 흘려보낸다(최대 100건 — 초과 공백은 replay 없이 목록/배지 API 재조회로 복구). " +
                "라이브 전송과 겹쳐 같은 알림이 중복 도착할 수 있으므로 클라이언트는 `id` 로 dedup 한다. " +
                "`silent-sync` 는 비영속이라 replay 되지 않는다. 상세 계약은 `notification-sse-spec.md` 참조.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description =
                    "SSE 스트림 시작 (`text/event-stream`). `notification` 이벤트 data payload 는 알림 종류별로 셰입이 다르고" +
                        "(파싱 알림은 출처별 `kind`·`tournamentId`·`tournamentItemId`), 스트림·다형 구조라 OpenAPI 로 표현이 어려워" +
                        " `notification-sse-spec.md` 로 문서화한다.",
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
    fun subscribe(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(
            name = "Last-Event-ID",
            `in` = ParameterIn.HEADER,
            required = false,
            description =
                "재연결 시 마지막으로 받은 `notification` 이벤트의 SSE `id`(알림 id). " +
                    "브라우저 `EventSource` 는 자동으로 싣고, APP 직접 구현은 마지막 id 를 저장했다가 싣는다. " +
                    "없거나 숫자가 아니면 첫 연결로 취급한다(replay 없음).",
        ) lastEventIdHeader: String?,
    ): SseEmitter
}
