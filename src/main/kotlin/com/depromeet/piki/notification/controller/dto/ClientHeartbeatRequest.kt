package com.depromeet.piki.notification.controller.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(description = "SSE 클라이언트 하트비트 요청")
data class ClientHeartbeatRequest(
    @field:Schema(
        description = "서버가 connect·heartbeat 이벤트 data 로 내려준 연결 번호(UUID)",
        example = "3f1c2b0e-7d4a-4c8b-9e2f-1a2b3c4d5e6f",
    )
    val connectionId: UUID,
)
