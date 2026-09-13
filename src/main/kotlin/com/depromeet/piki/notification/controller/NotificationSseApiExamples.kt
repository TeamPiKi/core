package com.depromeet.piki.notification.controller

import com.depromeet.piki.common.exception.CommonErrorCode
import com.depromeet.piki.common.openapi.OpenApiObjectMapper
import com.depromeet.piki.common.openapi.binds
import com.depromeet.piki.common.openapi.examples
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.notification.domain.NotificationException
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus

@Configuration
class NotificationSseApiExamples(
    private val openApiObjectMapper: OpenApiObjectMapper,
) {
    @Bean
    fun notificationSseOpenApiExamples(): OperationCustomizer =
        OperationCustomizer { operation, handlerMethod ->
            when {
                handlerMethod.binds(NotificationSseController::subscribe) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        // 200 은 text/event-stream 스트림이라 ApiResponseBody 래퍼 example 패턴(JSON 미디어타입)이 맞지 않는다.
                        // 이벤트 payload 형태는 NotificationSseApi 의 200 @Content schema 로 문서화하고, 여기선 401 만 등록한다.
                        // Security 필터 단 401 이라 unauthorized() 헬퍼로 COMMON-UNAUTHORIZED code 를 실어 실제 응답과 일치시킨다.
                        unauthorized("미인증")
                    }
                handlerMethod.binds(NotificationSseController::heartbeat) ->
                    operation.examples(openApiObjectMapper.delegate) {
                        add(status = HttpStatus.OK, name = "하트비트 반영", payload = ApiResponseBody.ok<Unit>())
                        // 누락·UUID 형식 오류 둘 다 역직렬화 실패(HttpMessageNotReadable)라 category 고정 문구가 detail 이다.
                        add(
                            status = HttpStatus.BAD_REQUEST,
                            name = "connectionId 없음 또는 UUID 형식 아님",
                            payload = ApiResponseBody.fail<Unit>(CommonErrorCode.INVALID_INPUT),
                        )
                        unauthorized("미인증")
                        add(NotificationException.unknownConnection(), name = "서버에 없는 연결 번호 - 재연결 필요")
                    }
            }
            operation
        }
}
