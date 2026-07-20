package com.depromeet.piki.admin.audit

// 감사 대상 행위 코드. 기능 액션(템플릿 수정·공지 발송)과 접근 제어(ACCESS_*, 슬랙-IP 게이트 #526)를 함께 남긴다.
enum class AdminAuditAction {
    TEMPLATE_UPDATE,

    // 추출 라우팅 정책(#9 디스패처) — 누가 어느 도메인을 어떤 정책으로 추가/삭제했는지.
    EXTRACTION_POLICY_UPDATE,

    // 출처 몰 표시명(#766) — 누가 어느 도메인의 표시명을 추가/교체/삭제했는지.
    SOURCE_PLATFORM_UPDATE,

    // 공지 행위자 추적(#558) — 등록·예약·예약취소·발송을 각각 다른 코드로 남겨 audit 에서 action 별로 가른다.
    // (이전엔 예약·취소·발송이 ANNOUNCEMENT_SEND 한 코드로 뭉쳐 detail 문자열로만 구분됐다.)
    ANNOUNCEMENT_REGISTER,
    ANNOUNCEMENT_EDIT,
    ANNOUNCEMENT_SCHEDULE,
    ANNOUNCEMENT_SCHEDULE_CANCEL,
    ANNOUNCEMENT_SEND,

    // 슬랙-IP 접근 게이트(#526) — 누가(슬랙명) 어느 IP 를 허용/해제했는지, 거부된 접근은 무엇인지.
    ACCESS_GRANTED,
    ACCESS_REVOKED,
    ACCESS_DENIED,
}
