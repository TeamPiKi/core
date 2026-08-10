package com.depromeet.piki.notification.controller

import com.depromeet.piki.common.exception.CommonErrorCode
import com.depromeet.piki.common.openapi.OpenApiObjectMapper
import com.depromeet.piki.common.openapi.binds
import com.depromeet.piki.common.openapi.examples
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.common.response.PageResponse
import com.depromeet.piki.notification.controller.dto.NotificationDeleteRequest
import com.depromeet.piki.notification.controller.dto.NotificationDeleteResponse
import com.depromeet.piki.notification.controller.dto.NotificationHistoryResponse
import com.depromeet.piki.notification.controller.dto.NotificationReadRequest
import com.depromeet.piki.notification.controller.dto.NotificationReadResponse
import com.depromeet.piki.notification.controller.dto.NotificationSsePayload
import com.depromeet.piki.notification.domain.NotificationException
import com.depromeet.piki.notification.domain.NotificationKind
import com.depromeet.piki.notification.domain.NotificationType
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import java.time.LocalDateTime

@Configuration
class NotificationHistoryApiExamples(
    private val openApiObjectMapper: OpenApiObjectMapper,
) {
    @Bean
    fun notificationHistoryOpenApiExamples(): OperationCustomizer =
        OperationCustomizer { operation, handlerMethod ->
            if (handlerMethod.binds(NotificationHistoryController::getHistory)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(
                        status = HttpStatus.OK,
                        name = "조회 성공 (안읽음·읽음 혼재, 마지막 페이지)",
                        payload =
                            ApiResponseBody.ok(
                                data =
                                    NotificationHistoryResponse(
                                        items = sampleItems,
                                        unreadCount = 2,
                                    ),
                                pageResponse = PageResponse(nextCursor = null, hasNext = false),
                            ),
                    )
                    add(
                        status = HttpStatus.OK,
                        name = "조회 성공 (다음 페이지 있음)",
                        payload =
                            ApiResponseBody.ok(
                                data =
                                    NotificationHistoryResponse(
                                        items = listOf(tournamentParsingItem),
                                        unreadCount = 1,
                                    ),
                                pageResponse = PageResponse(nextCursor = "1024", hasNext = true),
                            ),
                    )
                    add(
                        status = HttpStatus.OK,
                        name = "빈 알림함",
                        payload =
                            ApiResponseBody.ok(
                                data =
                                    NotificationHistoryResponse(
                                        items = emptyList(),
                                        unreadCount = 0,
                                    ),
                                pageResponse = PageResponse(nextCursor = null, hasNext = false),
                            ),
                    )
                    add(NotificationException.invalidCursor(), name = "유효하지 않은 cursor")
                    // size 가 Int 로 바인딩되지 않는 경우(숫자 아닌 값·Int 범위 초과) — 도메인 예외가 아니라 RESEH 경유 공통 400.
                    // detail 미지정 시 실제 응답과 동일하게 category.description 이 채워진다(single source, 손 detail 없음).
                    add(
                        status = HttpStatus.BAD_REQUEST,
                        name = "유효하지 않은 size 값",
                        payload = ApiResponseBody.fail<Unit>(CommonErrorCode.INVALID_INPUT),
                    )
                    unauthorized()
                }
            }
            if (handlerMethod.binds(NotificationHistoryController::read)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(
                        status = HttpStatus.OK,
                        name = "읽음 처리 성공 (처리 후 unreadCount 동봉)",
                        payload =
                            ApiResponseBody.ok(
                                data = NotificationReadResponse.of(unreadCount = 2),
                            ),
                    )
                    add(
                        status = HttpStatus.BAD_REQUEST,
                        name = "all 과 ids 동시 전송 / 둘 다 없음 / 빈 ids",
                        payload =
                            ApiResponseBody.fail<Unit>(
                                CommonErrorCode.INVALID_INPUT,
                                // @AssertTrue 위반은 GlobalExceptionHandler.detailOf 가 위반 필드의 메시지를 그대로 detail 로 내린다.
                                detail = NotificationReadRequest.VALID_SELECTION_MESSAGE,
                            ),
                    )
                    unauthorized()
                }
            }
            if (handlerMethod.binds(NotificationHistoryController::delete)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(
                        status = HttpStatus.OK,
                        name = "삭제 성공 (삭제 후 unreadCount 동봉)",
                        payload =
                            ApiResponseBody.ok(
                                data = NotificationDeleteResponse.of(unreadCount = 1),
                            ),
                    )
                    add(
                        status = HttpStatus.BAD_REQUEST,
                        name = "all 과 ids 동시 전송 / 둘 다 없음 / 빈 ids",
                        payload =
                            ApiResponseBody.fail<Unit>(
                                CommonErrorCode.INVALID_INPUT,
                                // @AssertTrue 위반은 GlobalExceptionHandler.detailOf 가 위반 필드의 메시지를 그대로 detail 로 내린다.
                                detail = NotificationDeleteRequest.VALID_SELECTION_MESSAGE,
                            ),
                    )
                    unauthorized()
                }
            }
            operation
        }

    // 토너먼트 알림 (라우팅 없음, refId 만) — 안읽음.
    // kind 는 리터럴로 박지 않고 payload 와 같은 파생(NotificationKind.of)을 거친다 — 분류가 바뀌면 example 이 따라온다.
    private val referenceItem =
        NotificationSsePayload.Reference(
            id = 1026,
            type = NotificationType.TOURNAMENT_JOINED,
            kind = NotificationKind.of(NotificationType.TOURNAMENT_JOINED, null),
            // 템플릿이 렌더한 실제 모양으로 둔다 — 문구는 DB 템플릿(V20260615015148 seed)이 소유한다.
            // TOURNAMENT_JOINED 는 "${actorName}님이 참가했어요" + body 빈 값이라, actorName 은 백오피스
            // 미리보기와 같은 샘플값(NotificationTemplateVariables)을 쓴다. body 는 전 타입이 빈 문자열이다.
            title = "홍길동님이 참가했어요",
            body = "",
            refId = 77,
            isRead = false,
            createdAt = LocalDateTime.of(2026, 6, 8, 10, 10, 0),
        )

    // 위시 출처 파싱 완료 알림 (라우팅 출처 WISH) — 읽음.
    private val wishParsingItem =
        NotificationSsePayload.WishParsing(
            id = 1025,
            type = NotificationType.ITEM_PARSING_COMPLETED,
            kind = NotificationKind.of(NotificationType.ITEM_PARSING_COMPLETED, NotificationKind.WISH),
            title = "에어 조던 1 미드 파싱이 완료되었어요",
            body = "",
            refId = 512,
            isRead = true,
            createdAt = LocalDateTime.of(2026, 6, 8, 10, 5, 0),
        )

    // 토너먼트 출처 파싱 완료 알림 (라우팅 출처 TOURNAMENT + 두 식별자) — 안읽음.
    private val tournamentParsingItem =
        NotificationSsePayload.TournamentRouted(
            id = 1024,
            type = NotificationType.ITEM_PARSING_COMPLETED,
            kind = NotificationKind.of(NotificationType.ITEM_PARSING_COMPLETED, NotificationKind.TOURNAMENT),
            title = "나이키 덩크 로우 파싱이 완료되었어요",
            body = "",
            refId = 513,
            isRead = false,
            createdAt = LocalDateTime.of(2026, 6, 8, 10, 0, 0),
            tournamentId = 99,
            tournamentItemId = 555,
        )

    private val sampleItems = listOf(referenceItem, wishParsingItem, tournamentParsingItem)
}
