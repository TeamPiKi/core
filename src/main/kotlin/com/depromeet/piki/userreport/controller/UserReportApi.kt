package com.depromeet.piki.userreport.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.userreport.controller.dto.ReportRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import java.util.UUID

private const val UNAUTHORIZED = "미인증 (JWT 토큰 없음 또는 유효하지 않음)"
private const val GUEST = "권한 없음 (게스트는 신고할 수 없음 — code: REPORT-001)"
private const val DELETED_USER = "요청자 본인이 탈퇴한 계정 (JWT 는 아직 유효하나 계정이 탈퇴 상태 — code: USER-003)"
private const val INVALID =
    "잘못된 요청 (자기 자신을 신고 — code: REPORT-002 · 기타가 아닌 사유에 상세 내용을 보냄 — code: REPORT-003 · " +
        "상세 내용이 300자 초과 — code: COMMON-INVALID-INPUT)"

// 상대의 부재와 탈퇴를 가르지 않는다 — 가르면 "그 계정이 있었다" 가 드러난다
private const val TARGET_NOT_FOUND = "상대를 찾을 수 없음 (없는 사용자 · 탈퇴한 사용자 — code: USER-001)"

@Tag(name = "Report", description = "사용자 신고 API")
interface UserReportApi {
    @Operation(
        summary = "사용자 신고",
        description =
            "상대를 신고한다. 사유는 하나만 고르고, 기타일 때만 상세 내용을 보낼 수 있다(생략 가능).\n\n" +
                "- **신고하면 그 상대를 차단한다.** 신고한 사람은 상대를 더 보고 싶지 않다는 뜻이라, " +
                "차단을 따로 누르게 하지 않는다. 이미 차단한 상대면 차단 상태는 그대로다.\n" +
                "- 같은 상대를 여러 번 신고할 수 있다. 막지 않는다 — 반복 신고 자체가 운영 판단의 신호다.\n" +
                "- 신고 내용은 상대에게 알려지지 않는다.\n" +
                "- 운영 알림은 신고가 저장된 뒤 따로 나간다. **알림이 실패해도 신고는 접수된다.**",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "접수 (같은 상대를 다시 신고해도 새 기록)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = INVALID,
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "401",
                description = UNAUTHORIZED,
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "403",
                description = GUEST,
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = TARGET_NOT_FOUND,
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = DELETED_USER,
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun reportUser(
        @Parameter(hidden = true) userId: UUID,
        @Parameter(description = "신고할 사용자 id") reportedId: UUID,
        request: ReportRequest,
    ): ApiResponseBody<Unit>
}
