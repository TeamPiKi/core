package com.depromeet.piki.announcement.controller

import com.depromeet.piki.announcement.controller.dto.AnnouncementResponse
import com.depromeet.piki.common.response.ApiResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Announcement", description = "공지사항 조회 (발송 완료된 공지만 노출)")
interface AnnouncementApi {
    @Operation(
        summary = "공지사항 목록 조회",
        description =
            "발송 완료(SENT)된 공지를 최신순으로 페이징 조회한다. 미발송(DRAFT·SCHEDULED·SENDING·MISSED) 공지는 노출되지 않는다. " +
                "응답 `pageResponse.nextCursor` 를 다음 요청의 `cursor` 로 넘겨 다음 페이지를 받는다(없으면 마지막 페이지).",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 (목록 + 페이지 정보)"),
            ApiResponse(responseCode = "400", description = "유효하지 않은 cursor (숫자로 변환 불가) (`ANNOUNCEMENT-002`)"),
            ApiResponse(responseCode = "401", description = "인증 필요 (로그인하지 않음)"),
        ],
    )
    fun list(
        @Parameter(description = "다음 페이지 커서(이전 응답의 nextCursor). 첫 페이지는 생략", example = "1024")
        cursor: String?,
        @Parameter(description = "페이지 크기(1~50, 기본 20)", example = "20")
        size: Int,
    ): ApiResponseBody<List<AnnouncementResponse>>

    @Operation(
        summary = "공지사항 단건 조회",
        description = "공지 id 로 발송 완료된 공지를 조회한다(알림 딥링크 착지). SENT 가 아니거나 없으면 404.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "404", description = "존재하지 않거나 아직 발송되지 않은 공지 (`ANNOUNCEMENT-001`)"),
            ApiResponse(responseCode = "401", description = "인증 필요 (로그인하지 않음)"),
        ],
    )
    fun get(
        @Parameter(description = "공지 id (알림 deeplink refId)", example = "42")
        announcementId: Long,
    ): ApiResponseBody<AnnouncementResponse>
}
