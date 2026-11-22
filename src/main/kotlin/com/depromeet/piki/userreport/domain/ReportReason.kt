package com.depromeet.piki.userreport.domain

// 신고 사유. 화면은 이 중 하나만 고를 수 있다(#1291). 문구는 기획 핸드오프의 목록 그대로다.
//
// OTHER 만 detail(기타 내용)을 받는다 — 나머지 사유에 detail 이 함께 오면 입력 경계가 400 으로 막는다.
//
// label 은 운영이 읽는 문구다(백오피스 화면). 앱 화면의 문구는 클라이언트가 자기 쪽에 들고 있고,
// API 는 enum 이름만 주고받으므로 이 값이 앱 문구와 달라져도 계약이 깨지지 않는다.
enum class ReportReason(
    val label: String,
) {
    SPAM_OR_AD("스팸·광고성 콘텐츠"),
    INAPPROPRIATE_ITEM_OR_IMAGE("부적절한 상품·이미지"),
    SEXUAL_CONTENT("음란하거나 선정적인 콘텐츠"),
    ABUSIVE_OR_HATE_SPEECH("욕설·혐오 표현"),
    IMPERSONATION("다른 사람 사칭"),
    OTHER("기타"),
    ;

    fun allowsDetail(): Boolean = this == OTHER
}
