package com.depromeet.piki.tournament.controller.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

@Schema(description = "완료된 토너먼트에 코드로 입장 (#1013)")
data class CreateFromPlayCodeRequest(
    // 초대 코드와 같은 값이다. 완료 상태에서는 그 코드가 플레이 입장 주소로도 쓰인다 - 코드는
    // 생성 시점에 발급돼 바뀌지 않으므로 새로 발급할 것이 없다.
    //
    // 형식 검증을 여기 두면 오타가 서비스에 닿기 전에 걸리고, 로그에도 "형식은 맞는데 없는 코드"
    // 와 "형식부터 틀린 값" 이 구분돼 남는다.
    @field:NotBlank(message = JoinTournamentAsGuestRequest.INVITE_CODE_PATTERN_MESSAGE)
    @field:Pattern(regexp = "[A-Z]{3}\\d{3}", message = JoinTournamentAsGuestRequest.INVITE_CODE_PATTERN_MESSAGE)
    @field:Schema(description = "6자리 코드 (영문 대문자 3 + 숫자 3)", example = "ABC123")
    val code: String,
)
