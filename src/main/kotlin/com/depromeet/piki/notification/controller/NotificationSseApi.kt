package com.depromeet.piki.notification.controller

import com.depromeet.piki.common.response.ApiResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
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
                "| `notification` | 알림 1건마다 | `type` 으로 화면을, 전 알림 공통 필드 `kind`(`WISH`\\|`TOURNAMENT`\\|`SYSTEM`)로 " +
                "라벨·아이콘과 딥링크 출처를 분기. payload 셰입과 아이템 좌표(`tournamentId`·`tournamentItemId`)가 실리는 조건은 `notification-sse-spec.md` 참조 |\n" +
                "| `silent-sync` | 화면 갱신 사건마다 | 조용한 화면 갱신 신호(알림 아님). payload 의 `type` 으로 사건을 분기한다: " +
                "`TOURNAMENT_ITEM_PARSED`(`{type, tournamentId, tournamentItemId, status}`, status=`READY`\\|`FAILED`) · " +
                "`UNREAD_COUNT_CHANGED`(`{type, unreadCount}`, 읽음 후 멀티 디바이스 인앱 배지 동기화). 알림센터·푸시 없이 SSE 로만 흐른다. `notification-sse-spec.md` 참조 |\n" +
                "| `(주석 ping)` | 약 30초 간격 | 하트비트. 연결 유지용이며 data 이벤트가 아니다 |\n\n" +
                "- 토너먼트 알림은 해당 토너먼트 참여자에게만 fan-out 되므로, **자기 스트림 1개만 구독**하면 토너먼트·개인 알림이 모두 도착한다.\n" +
                "- 연결은 **30분 후 타임아웃**되며, 클라이언트는 끊기면 재연결한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description =
                    "SSE 스트림 시작 (`text/event-stream`). `notification` 이벤트 data payload 는 공통 필드(`id`·`type`·`kind`·`title`·`body`·" +
                        "`refId`·`isRead`·`createdAt`) 위에 알림 종류별로 딥링크 좌표(토너먼트 `tournamentId`·`tournamentItemId` / 위시 `wishId`)가 더 붙어 셰입이 갈리고," +
                        " 스트림·다형 구조라 OpenAPI 로 표현이 어려워 `notification-sse-spec.md` 로 문서화한다.",
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
    ): SseEmitter
}
