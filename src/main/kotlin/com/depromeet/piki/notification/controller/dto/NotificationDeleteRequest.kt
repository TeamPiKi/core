package com.depromeet.piki.notification.controller.dto

import com.depromeet.piki.notification.service.dto.NotificationDeleteCommand
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.AssertTrue

// 삭제 요청 — all=true(모두 삭제) XOR ids(지정 단건/다건). 읽음(NotificationReadRequest)과 같은 계약을 미러링한다.
// 정확히 하나만 유효(둘 다·둘 다 없음·빈 ids 는 400). 삭제는 읽음 무관 하드삭제이고 본인 소유만 걸러 멱등이다.
@Schema(description = "알림 삭제 요청 — all=true(모두) 또는 ids(지정) 중 정확히 하나")
data class NotificationDeleteRequest(
    @field:Schema(description = "true 면 본인 알림 전부 삭제 (읽음 무관, 모두 삭제 버튼). ids 와 동시 사용 불가", nullable = true, example = "true")
    val all: Boolean? = null,
    @field:Schema(description = "삭제할 알림 id 목록 (단건은 [id] 1개, 다건은 [id, ...]). all 과 동시 사용 불가", nullable = true, example = "[1024]")
    val ids: List<Long>? = null,
) {
    // all XOR ids — 정확히 한쪽만. 둘 다·둘 다 없음·빈 ids 는 400(입력 경계 계약, NotificationReadRequest 와 동형).
    @get:JsonIgnore
    @get:AssertTrue(message = VALID_SELECTION_MESSAGE)
    val validSelection: Boolean
        get() {
            val byAll = all == true
            val byIds = !ids.isNullOrEmpty()
            return byAll xor byIds
        }

    // validSelection 통과 후 호출 — all=true 면 All, 아니면 ids 는 non-null·non-empty 가 보장된다(불변식).
    fun toCommand(): NotificationDeleteCommand =
        if (all == true) {
            NotificationDeleteCommand.All
        } else {
            NotificationDeleteCommand.Ids(requireNotNull(ids) { "validSelection 통과 시 ids 는 non-null 이다" })
        }

    companion object {
        // Bean Validation 위반 메시지의 single source — ApiExamples 가 같은 상수를 참조한다(detail single-source).
        // ids 개수 상한은 두지 않는다 — 대량 삭제는 all=true 가 담당하고, ids 는 본인 알림 선택(보통 소수)이라
        // 인증·본인 한정·멱등 + HTTP 본문 크기 상한으로 이미 안전하다(임의 캡으로 정상 요청을 400 으로 막지 않는다).
        // 응답 detail 은 사용자 대면이라 친화 문구로 둔다(어느 필드가 잘못됐는지는 앱이 자기 요청으로 안다).
        const val VALID_SELECTION_MESSAGE = "요청을 처리하지 못했어요."
    }
}
