package com.depromeet.piki.tournament.controller.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

@Schema(description = "토너먼트 참여 닉네임 수정 요청 — 이 토너먼트에서만 보이는 표시명을 바꾼다(프로필 닉네임 불변)")
data class UpdateTournamentNicknameRequest(
    @field:Size(min = 1, max = 10, message = NICKNAME_SIZE_MESSAGE)
    @field:Schema(description = "이 토너먼트에서 쓸 닉네임 (1~10자)", example = "라떼왕")
    val nickname: String,
) {
    // Bean Validation 위반 메시지의 single source. OpenAPI example(TournamentApiExamples)이 같은 상수를 참조해
    // "필드 검증 문구가 @field 와 example 두 곳에서 따로 노는" 어긋남을 컴파일 타임에 막는다.
    companion object {
        const val NICKNAME_SIZE_MESSAGE = "닉네임은 1~10자 사이로 입력해 주세요."
    }
}
