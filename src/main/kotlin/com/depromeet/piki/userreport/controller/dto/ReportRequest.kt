package com.depromeet.piki.userreport.controller.dto

import com.depromeet.piki.userreport.domain.ReportReason
import com.depromeet.piki.userreport.domain.UserReport
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

@Schema(description = "사용자 신고 요청")
data class ReportRequest(
    @field:Schema(description = "신고 사유. 하나만 고른다", example = "SPAM_OR_AD")
    val reason: ReportReason,
    // 기타일 때만 받는다. 비어 있어도 신고는 된다 — 체크만 하고 입력을 안 해도 보낼 수 있는 화면이다
    @field:Size(max = UserReport.MAX_DETAIL_LENGTH, message = DETAIL_SIZE_MESSAGE)
    @field:Schema(description = "기타 사유의 상세 내용. 기타일 때만 보낼 수 있고 생략 가능", example = "사이트가 아닌 개인 계좌로 결제를 유도했어요.")
    val detail: String? = null,
) {
    // Bean Validation 위반 메시지의 single source. OpenAPI example 이 같은 상수를 참조해
    // "문구가 두 곳에서 따로 노는" 어긋남을 컴파일 타임에 막는다
    companion object {
        const val DETAIL_SIZE_MESSAGE = "상세 내용은 ${UserReport.MAX_DETAIL_LENGTH}자까지 입력할 수 있어요."
    }
}
