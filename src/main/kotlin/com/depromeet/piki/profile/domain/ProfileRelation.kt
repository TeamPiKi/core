package com.depromeet.piki.profile.domain

// 보는 사람과 주인의 관계. 화면이 버튼과 상단 아이콘을 이걸로 가른다(#1285 프로필 공통).
//
// BLOCKED 는 **내가 상대를 차단한** 경우만이다. 상대가 나를 차단한 경우는 NOT_FRIEND 로 내려간다 —
// 가르면 "저 사람이 나를 차단했다" 가 드러난다. 내가 건 차단은 내가 한 일이라 숨기지 않는다.
enum class ProfileRelation {
    SELF,
    FRIEND,
    NOT_FRIEND,
    BLOCKED,
}
