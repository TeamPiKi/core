package com.depromeet.piki.userreport.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.userreport.controller.dto.ReportRequest
import com.depromeet.piki.userreport.service.UserReportService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/reports")
class UserReportController(
    private val userReportService: UserReportService,
) : UserReportApi {
    // 같은 상대를 여러 번 신고할 수 있어 "이미 있음" 이 없다. 매번 새 기록이라 201 이다
    @PostMapping("/users/{reportedId}")
    @ResponseStatus(HttpStatus.CREATED)
    override fun reportUser(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable reportedId: UUID,
        @Valid @RequestBody request: ReportRequest,
    ): ApiResponseBody<Unit> {
        userReportService.report(userId, reportedId, request.reason, request.detail)
        return ApiResponseBody.created()
    }
}
