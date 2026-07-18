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
import com.depromeet.piki.notification.domain.NotificationCategory
import com.depromeet.piki.notification.domain.NotificationException
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
                                        unreadCountByCategory = mapOf(NotificationCategory.ACTIVITY to 1L, NotificationCategory.SYSTEM to 1L),
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
                                        unreadCountByCategory = mapOf(NotificationCategory.ACTIVITY to 0L, NotificationCategory.SYSTEM to 1L),
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
                                        unreadCountByCategory = mapOf(NotificationCategory.ACTIVITY to 0L, NotificationCategory.SYSTEM to 0L),
                                    ),
                                pageResponse = PageResponse(nextCursor = null, hasNext = false),
                            ),
                    )
                    add(NotificationException.invalidCursor(), name = "유효하지 않은 cursor")
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
                                data = NotificationReadResponse.of(
                                    mapOf(NotificationCategory.ACTIVITY to 1L, NotificationCategory.SYSTEM to 1L),
                                ),
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
                                data = NotificationDeleteResponse.of(
                                    mapOf(NotificationCategory.ACTIVITY to 1L, NotificationCategory.SYSTEM to 0L),
                                ),
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

    // 토너먼트 알림 (라우팅 없음, refId 만) — 활동, actor 있어 imageUrl=프사 snapshot, 안읽음.
    private val referenceItem =
        NotificationSsePayload.Reference(
            id = 1026,
            type = NotificationType.TOURNAMENT_JOINED,
            category = NotificationCategory.ACTIVITY,
            title = "토너먼트에 참가했어요",
            body = "지금 바로 픽을 시작해보세요",
            imageUrl = "https://images.piki/profiles/abc/3f7c.png",
            refId = 77,
            isRead = false,
            createdAt = LocalDateTime.of(2026, 6, 8, 10, 10, 0),
        )

    // 위시 출처 파싱 완료 알림 (kind=WISH) — 시스템, actor 없어 imageUrl=defaultPushImg(피키 로고), 읽음.
    private val wishParsingItem =
        NotificationSsePayload.WishParsing(
            id = 1025,
            type = NotificationType.ITEM_PARSING_COMPLETED,
            category = NotificationCategory.SYSTEM,
            title = "상품 정보가 준비됐어요",
            body = "에어 조던 1 미드",
            imageUrl = "https://images.piki/defaults/push-icon.svg",
            refId = 512,
            isRead = true,
            createdAt = LocalDateTime.of(2026, 6, 8, 10, 5, 0),
        )

    // 토너먼트 출처 파싱 완료 알림 (kind=TOURNAMENT + 두 식별자) — 시스템, imageUrl=defaultPushImg, 안읽음.
    private val tournamentParsingItem =
        NotificationSsePayload.TournamentRouted(
            id = 1024,
            type = NotificationType.ITEM_PARSING_COMPLETED,
            category = NotificationCategory.SYSTEM,
            title = "토너먼트 아이템이 준비됐어요",
            body = "나이키 덩크 로우",
            imageUrl = "https://images.piki/defaults/push-icon.svg",
            refId = 513,
            isRead = false,
            createdAt = LocalDateTime.of(2026, 6, 8, 10, 0, 0),
            tournamentId = 99,
            tournamentItemId = 555,
        )

    private val sampleItems = listOf(referenceItem, wishParsingItem, tournamentParsingItem)
}
