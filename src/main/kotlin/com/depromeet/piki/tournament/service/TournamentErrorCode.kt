package com.depromeet.piki.tournament.service

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode

// TournamentException 의 code 배정표(에픽 #728). 번호는 append-only — 재배치·결번 침범 금지.
// code·category·message 를 한 엔트리에 모아 single source 로 둔다: status 는 category.httpStatus 로,
// 응답 detail·로그·OpenAPI 문서는 message 로 파생된다.
//
// 001~032 는 tournament code 초안(에픽 부록)의 순서를 그대로 승계하고, 033(invalidLimit)은 그 뒤 목록 조회 limit
// 검증이 추가돼 append 로 붙였다(소스 선언 위치는 003 뒤지만 번호 안정성 위해 뒤에 부여).
// 이 도메인 이관으로 409(CONFLICT) 응답이 처음으로 도메인 code(TOURNAMENT-0xx)를 싣게 된다 —
// #744 는 CONFLICT 에 공통 code 를 두지 않아 그동안 code=null 이었다.
enum class TournamentErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
) : ErrorCode {
    FORBIDDEN_TOURNAMENT("TOURNAMENT-001", ErrorCategory.FORBIDDEN, "이 토너먼트에 접근할 수 없어요."),
    NOT_FOUND_TOURNAMENT("TOURNAMENT-002", ErrorCategory.NOT_FOUND, "토너먼트를 찾을 수 없어요. 이미 삭제됐을 수 있어요."),
    INVALID_WINNER("TOURNAMENT-003", ErrorCategory.INVALID_INPUT, "올바르지 않은 선택이에요."),
    INVALID_TOURNAMENT_ITEM("TOURNAMENT-004", ErrorCategory.INVALID_INPUT, "이 토너먼트에 없는 아이템이에요."),
    NOT_PENDING_TOURNAMENT("TOURNAMENT-005", ErrorCategory.CONFLICT, "토너먼트가 시작되기 전에만 할 수 있어요."),
    NOT_IN_PROGRESS_TOURNAMENT("TOURNAMENT-006", ErrorCategory.CONFLICT, "토너먼트가 진행 중일 때만 할 수 있어요."),
    INVALID_ITEM_COUNT("TOURNAMENT-007", ErrorCategory.INVALID_INPUT, "아이템은 2~32개 사이로 담아주세요."),
    TOO_MANY_TOURNAMENT_ITEMS("TOURNAMENT-008", ErrorCategory.INVALID_INPUT, "아이템은 최대 32개까지 담을 수 있어요."),
    DUPLICATE_TOURNAMENT_ITEM("TOURNAMENT-009", ErrorCategory.CONFLICT, "이미 담은 아이템이에요."),
    NOT_FOUND_TOURNAMENT_ITEM("TOURNAMENT-010", ErrorCategory.NOT_FOUND, "토너먼트 아이템을 찾을 수 없어요."),
    NOT_FOUND_ITEMS("TOURNAMENT-011", ErrorCategory.NOT_FOUND, "존재하지 않는 아이템이 포함되어 있어요."),
    ITEM_NOT_READY("TOURNAMENT-012", ErrorCategory.CONFLICT, "아직 정보를 가져오는 중인 상품이에요. 잠시 후 추가해 주세요."),
    ITEM_NOT_READY_TO_START("TOURNAMENT-013", ErrorCategory.CONFLICT, "아직 준비 중인 상품이 있어요. 모두 준비되면 시작할 수 있어요."),
    ITEM_PRICE_REQUIRED("TOURNAMENT-014", ErrorCategory.CONFLICT, "가격 정보가 없는 상품이 있어요. 직접 입력 후 시작해 주세요."),
    INVALID_IMAGE_COUNT("TOURNAMENT-015", ErrorCategory.INVALID_INPUT, "이미지는 1~5장만 올릴 수 있어요."),
    ITEM_NOT_IN_WISHLIST("TOURNAMENT-016", ErrorCategory.FORBIDDEN, "위시에 저장된 아이템만 담을 수 있어요."),
    ELIMINATED_TOURNAMENT_ITEM("TOURNAMENT-017", ErrorCategory.CONFLICT, "이미 탈락한 아이템이에요."),
    INVALID_CURRENT_ROUND("TOURNAMENT-018", ErrorCategory.INVALID_INPUT, "지금은 진행할 수 없는 단계예요."),
    IN_PROGRESS_TOURNAMENT_CANNOT_BE_DELETED("TOURNAMENT-019", ErrorCategory.CONFLICT, "토너먼트가 끝난 후 삭제할 수 있어요."),
    INVALID_INVITE_CODE("TOURNAMENT-020", ErrorCategory.INVALID_INPUT, "초대 코드가 올바르지 않아요."),
    INVITE_EXPIRED("TOURNAMENT-021", ErrorCategory.CONFLICT, "초대 링크가 만료됐어요."),
    ALREADY_PARTICIPANT("TOURNAMENT-022", ErrorCategory.CONFLICT, "이미 참여 중인 토너먼트예요."),
    NOT_COMPLETED_TOURNAMENT("TOURNAMENT-023", ErrorCategory.CONFLICT, "완료된 토너먼트에서만 할 수 있어요."),
    CLONED_TOURNAMENT_CANNOT_SHARE_PLAY_LINK("TOURNAMENT-024", ErrorCategory.FORBIDDEN, "플레이 링크로 참여한 토너먼트는 공유 링크를 만들 수 없어요."),
    PLAY_LINK_ALREADY_CREATED("TOURNAMENT-025", ErrorCategory.CONFLICT, "이미 플레이 링크가 만들어진 토너먼트예요."),
    PLAY_LINK_NOT_CREATED("TOURNAMENT-026", ErrorCategory.NOT_FOUND, "아직 플레이 링크가 만들어지지 않은 토너먼트예요."),
    PLAY_LINK_EXPIRED("TOURNAMENT-027", ErrorCategory.CONFLICT, "플레이 링크가 만료됐어요."),
    GROUP_RESULT_NOT_AVAILABLE("TOURNAMENT-028", ErrorCategory.CONFLICT, "완료된 토너먼트에서만 결과를 볼 수 있어요."),
    ALREADY_CLONED("TOURNAMENT-029", ErrorCategory.CONFLICT, "이미 이 플레이 링크로 만든 토너먼트가 있어요."),
    PARTICIPANT_LIMIT_EXCEEDED("TOURNAMENT-030", ErrorCategory.CONFLICT, "참여 인원이 가득 찼어요."),
    CLONED_TOURNAMENT_CANNOT_VIEW_GROUP_RESULT("TOURNAMENT-031", ErrorCategory.FORBIDDEN, "플레이 링크로 참여한 토너먼트에서는 친구 결과를 볼 수 없어요."),
    CLONED_TOURNAMENT_CANNOT_ADD_ITEMS("TOURNAMENT-032", ErrorCategory.FORBIDDEN, "플레이 링크로 만든 토너먼트에는 아이템을 추가할 수 없어요."),
    INVALID_LIMIT("TOURNAMENT-033", ErrorCategory.INVALID_INPUT, "조회 개수는 1 이상이어야 해요."),

    // 034~035 는 매치 로직 백엔드 이관(#683)에서 추가됐다. 브래킷 무결성 검증이 처음 들어오며 생긴 두 사유다.
    INVALID_MATCH_PAIR("TOURNAMENT-034", ErrorCategory.INVALID_INPUT, "지금 대결할 수 있는 조합이 아니에요."),
    MATCH_ALREADY_RECORDED("TOURNAMENT-035", ErrorCategory.CONFLICT, "이미 결과가 기록된 대결이에요."),

    // 036 은 게스트 권한 정리(#339)에서 추가됐다. 토너먼트 생성은 회원 전용이 되고, 생성된 토너먼트에
    // 아이템을 담는 것과 플레이는 게스트에게 그대로 열려 있다 — "게스트는 소비만, 생산은 회원만".
    //
    // 지금은 아무도 던지지 않는다. 클라이언트가 이 code 를 처리할 때까지 게이트를 임시로 걷었다(#965).
    // 엔트리를 남겨 두는 것은 곧 되살릴 것이기 때문이고, 번호는 append-only 라 어차피 재사용하지 않는다.
    // 게이트를 되살리는 자리는 TournamentService.rejectIfDeleted 주석에 적혀 있다.
    GUEST_CANNOT_CREATE_TOURNAMENT("TOURNAMENT-036", ErrorCategory.FORBIDDEN, "토너먼트 만들기는 회원만 이용할 수 있어요."),

    // 037 도 #339. 차감 주체는 토너먼트 오너지만 이 응답은 참여자(게스트 포함) 누구나 받을 수 있으므로,
    // 문구에 "오너의 사용량" 을 드러내지 않는다 — 남의 사용량은 요청자에게 알릴 정보가 아니다.
    ITEM_QUOTA_EXCEEDED(
        "TOURNAMENT-037",
        ErrorCategory.TOO_MANY_REQUESTS,
        "이 토너먼트에는 지금 아이템을 추가할 수 없어요. 잠시 후 다시 시도해 주세요.",
    ),

    // 038 은 플레이 링크 클론의 아이템 단건 조회 정합(#977)에서 추가됐다. 클론은 원본 아이템을 이어받아 조회는 되지만,
    // 수정·삭제하면 원본을 건드리게 되므로 아이템 추가 금지(032)와 같은 결로 막는다 — 옛 "직접 소속" 스코프 체크의
    // 혼란스러운 404 를 의도된 정책 403 으로 대체한다.
    CLONED_TOURNAMENT_CANNOT_MODIFY_ITEMS(
        "TOURNAMENT-038",
        ErrorCategory.FORBIDDEN,
        "플레이 링크로 만든 토너먼트에서는 아이템을 수정하거나 삭제할 수 없어요.",
    ),
}
