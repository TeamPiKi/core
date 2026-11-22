package com.depromeet.piki.userreport.controller

import com.depromeet.piki.common.exception.CommonErrorCode
import com.depromeet.piki.common.openapi.OpenApiObjectMapper
import com.depromeet.piki.common.openapi.binds
import com.depromeet.piki.common.openapi.examples
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.user.domain.UserException
import com.depromeet.piki.userreport.controller.dto.ReportRequest
import com.depromeet.piki.userreport.domain.UserReportException
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus

@Configuration
class UserReportApiExamples(
    private val openApiObjectMapper: OpenApiObjectMapper,
) {
    @Bean
    fun userReportOpenApiExamples(): OperationCustomizer =
        OperationCustomizer { operation, handlerMethod ->
            if (handlerMethod.binds(UserReportController::reportUser)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(status = HttpStatus.CREATED, name = "접수 성공", payload = ApiResponseBody.created<Unit>())
                    add(UserReportException.selfNotAllowed(), name = "자기 자신을 신고")
                    add(UserReportException.detailNotAllowed(), name = "기타가 아닌 사유에 상세 내용을 보냄")
                    add(
                        status = HttpStatus.BAD_REQUEST,
                        name = "상세 내용 300자 초과",
                        payload =
                            ApiResponseBody.fail<Unit>(
                                CommonErrorCode.INVALID_INPUT,
                                // 실제 응답은 GlobalExceptionHandler.detailOf 가 위반 필드의 메시지를 그대로 싣는다
                                detail = ReportRequest.DETAIL_SIZE_MESSAGE,
                            ),
                    )
                    unauthorized()
                    add(UserReportException.guestCannotUse(), name = "게스트의 신고 거부")
                    add(UserException.notFound(), name = "없는 사용자 · 탈퇴한 상대")
                    add(UserException.deletedUser(), name = "요청자 본인이 탈퇴한 계정")
                }
            }
            operation
        }
}
