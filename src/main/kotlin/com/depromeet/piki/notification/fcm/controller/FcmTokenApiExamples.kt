package com.depromeet.piki.notification.fcm.controller

import com.depromeet.piki.common.exception.CommonErrorCode
import com.depromeet.piki.common.openapi.OpenApiObjectMapper
import com.depromeet.piki.common.openapi.binds
import com.depromeet.piki.common.openapi.examples
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.notification.fcm.controller.dto.FcmDeviceUnregisterRequest
import com.depromeet.piki.notification.fcm.controller.dto.FcmTokenRegisterRequest
import com.depromeet.piki.user.domain.UserException
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus

@Configuration
class FcmTokenApiExamples(
    private val openApiObjectMapper: OpenApiObjectMapper,
) {
    @Bean
    fun fcmTokenOpenApiExamples(): OperationCustomizer =
        OperationCustomizer { operation, handlerMethod ->
            if (handlerMethod.binds(FcmTokenController::register)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(
                        status = HttpStatus.OK,
                        name = "등록/갱신 성공",
                        payload = ApiResponseBody.ok<Unit>(),
                    )
                    add(
                        status = HttpStatus.BAD_REQUEST,
                        name = "토큰 또는 기기 식별자 누락",
                        payload =
                            ApiResponseBody.fail<Unit>(
                                CommonErrorCode.INVALID_INPUT,
                                detail = FcmTokenRegisterRequest.BLANK_MESSAGE,
                            ),
                    )
                    // 탈퇴 유저 등록 거부(#776) — status·code·detail 을 예외 정의(UserException.deletedUser)에서 자동 추출한다.
                    add(UserException.deletedUser(), name = "탈퇴한 계정 (denylist 마킹 실패 창)")
                    unauthorized()
                }
            }
            if (handlerMethod.binds(FcmTokenController::unregister)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(
                        status = HttpStatus.OK,
                        name = "해제 성공",
                        payload = ApiResponseBody.ok<Unit>(),
                    )
                    add(
                        status = HttpStatus.BAD_REQUEST,
                        name = "기기 식별자 누락",
                        payload =
                            ApiResponseBody.fail<Unit>(
                                CommonErrorCode.INVALID_INPUT,
                                detail = FcmDeviceUnregisterRequest.DEVICE_ID_BLANK_MESSAGE,
                            ),
                    )
                    unauthorized()
                }
            }
            operation
        }
}
