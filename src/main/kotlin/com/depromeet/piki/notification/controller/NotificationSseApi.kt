package com.depromeet.piki.notification.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.notification.controller.dto.ClientHeartbeatRequest
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
                "| `connect` | 구독 직후 1회 | `data=<connectionId>`. 연결 성립 신호이자 이 연결의 번호(UUID). " +
                "클라이언트는 이 번호를 보관해 하트비트(`POST /heartbeat`)에 되돌려 보낸다 |\n" +
                "| `notification` | 알림 1건마다 | `type` 으로 화면을, 전 알림 공통 필드 `kind`(`WISH`\\|`TOURNAMENT`\\|`SYSTEM`)로 " +
                "라벨·아이콘과 딥링크 출처를 분기. payload 셰입과 아이템 좌표(`tournamentId`·`tournamentItemId`)가 실리는 조건은 `notification-sse-spec.md` 참조 |\n" +
                "| `silent-sync` | 화면 갱신 사건마다 | 조용한 화면 갱신 신호(알림 아님). payload 의 `type` 으로 사건을 분기한다: " +
                "`TOURNAMENT_ITEM_PARSED`(`{type, tournamentId, tournamentItemId, status}`, status=`READY`\\|`FAILED`) · " +
                "`UNREAD_COUNT_CHANGED`(`{type, unreadCount}`, 읽음 후 멀티 디바이스 인앱 배지 동기화). 알림센터·푸시 없이 SSE 로만 흐른다. `notification-sse-spec.md` 참조 |\n" +
                "| `heartbeat` | 약 30초 간격 | 서버 ping. `data=<connectionId>`(connect 와 같은 번호). **60초 동안 안 오면 스트림이 죽은 것이므로 재연결**한다 |\n\n" +
                "- 토너먼트 알림은 해당 토너먼트 참여자에게만 fan-out 되므로, **자기 스트림 1개만 구독**하면 토너먼트·개인 알림이 모두 도착한다.\n" +
                "- 연결은 **30분 후 타임아웃**되며, 클라이언트는 끊기면 재연결한다.\n" +
                "- 서버 ping 은 프록시까지 도달한 것만 확인되므로, 클라이언트도 `POST /heartbeat` 로 자기 생존을 알려야 한다(아래).",
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

    @Operation(
        summary = "SSE 클라이언트 하트비트",
        description =
            "SSE 연결이 살아 있음을 클라이언트가 서버에 알린다. 서버 ping(`heartbeat` 이벤트)은 프록시까지 도달한 것만 확인되고, " +
                "클라이언트가 비정상 종료돼도 서버는 한참 뒤에야 알기 때문에 반대 방향 신호가 필요하다.\n\n" +
                "- **SSE 연결 중이고 앱이 포그라운드일 때 30초마다** 호출한다. " +
                "본문의 `connectionId` 는 `connect`·`heartbeat` 이벤트 data 로 받은 번호다.\n" +
                "- 실패해도 재시도하지 않는다. 다음 주기에 다시 보내면 된다. `401` 은 다른 API 와 같이 토큰 갱신 대상이다" +
                "(SSE 연결 30분이 액세스 토큰 15분보다 길어 연결 중 한 번은 만난다).\n" +
                "- 서버는 하트비트를 한 번이라도 보낸 연결에 한해 **60초 동안 하트비트가 없으면 정상 종료**한다" +
                "(검사가 30초 주기라 실제 종료는 60초에서 90초 사이). 클라이언트는 기존 재연결 로직대로 다시 붙는다. " +
                "앱이 백그라운드에서 돌아왔을 때 끊겨 있는 것은 의도된 동작이다.\n" +
                "- `409`(`NOTIFICATION-002`)는 그 번호의 연결이 서버에 없다는 뜻이다(배포로 서버가 바뀌었거나 이미 정리됨). **즉시 재연결**한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "하트비트 반영 (data 없음)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "잘못된 요청 (connectionId 없음 · UUID 형식이 아님)",
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
                responseCode = "409",
                description = "그 번호의 SSE 연결이 서버에 없음 (`NOTIFICATION-002`) - 재연결 필요",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun heartbeat(
        @Parameter(hidden = true) userId: UUID,
        request: ClientHeartbeatRequest,
    ): ApiResponseBody<Unit>
}
