package com.depromeet.piki.userreport.domain

// 신고 사유. 화면은 이 중 하나만 고를 수 있다(#1291). 문구는 기획 핸드오프의 목록 그대로다.
//
// OTHER 만 detail(기타 내용)을 받는다 — 나머지 사유에 detail 이 함께 오면 입력 경계가 400 으로 막는다.
enum class ReportReason {
    SPAM_OR_AD,
    INAPPROPRIATE_ITEM_OR_IMAGE,
    SEXUAL_CONTENT,
    ABUSIVE_OR_HATE_SPEECH,
    IMPERSONATION,
    OTHER,
    ;

    fun allowsDetail(): Boolean = this == OTHER
}
