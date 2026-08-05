package com.depromeet.piki.tournament.controller.dto

import com.depromeet.piki.tournament.service.TOURNAMENT_INVITE_DEFAULT_DURATION_MINUTES
import com.depromeet.piki.tournament.service.TOURNAMENT_INVITE_MAX_DURATION_MINUTES
import com.depromeet.piki.tournament.service.dto.CreateTournament
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class CreateTournamentRequest(
    @field:NotBlank(message = NAME_BLANK_MESSAGE)
    val name: String,
    @field:Min(value = 1, message = INVITE_DURATION_MIN_MESSAGE)
    @field:Max(value = TOURNAMENT_INVITE_MAX_DURATION_MINUTES, message = INVITE_DURATION_MAX_MESSAGE)
    val inviteDurationMinutes: Long? = null,
) {
    fun toCreateTournament(): CreateTournament =
        CreateTournament(
            name = name,
            inviteDurationMinutes = inviteDurationMinutes ?: TOURNAMENT_INVITE_DEFAULT_DURATION_MINUTES,
        )

    companion object {
        const val NAME_BLANK_MESSAGE = "토너먼트 이름을 입력해 주세요."
        const val INVITE_DURATION_MIN_MESSAGE = "초대 유효 시간은 1분 이상으로 입력해 주세요."
        const val INVITE_DURATION_MAX_MESSAGE = "초대 유효 시간은 24시간 이내로 입력해 주세요."
    }
}
