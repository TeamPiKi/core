package com.depromeet.piki.item.domain

// 추출 버전의 출처(#825 결정 4). 카드·가격 추적은 항상 마지막 SERVER* READY 를 향하고,
// MANUAL 은 이력에 남되 기본 뷰에서 접힌다 — 그 구분의 근거.
// SERVER 와 SERVER_LLM 을 가르는 이유: LLM 경로는 같은 페이지를 같은 날 재추출해도 값이 달라질 수 있어
// (비결정성 실측), 이후 "LLM 추출분은 가격 변동 알림 제외" 같은 정책이 이 구분 위에서 열린다.
// 기존 행(도입 전)은 출처 소급이 불가능해 컬럼이 null 이다 — enum 에 UNKNOWN 을 두지 않는 이유:
// "행 없음/null = 모름" 규약이면 충분하고, 쓰기 경로가 UNKNOWN 을 실수로 저장할 여지를 없앤다.
enum class ItemSnapshotSource {
    // 구조화 파서(JSON-LD·OpenGraph 등) 추출 — 결정론적.
    SERVER,

    // LLM(Gemini) 추출 — 비결정성이 있어 신뢰 정책이 다를 수 있다.
    SERVER_LLM,

    // 사용자 수기 입력. edited_by 에 편집자 userId 가 함께 기록된다.
    MANUAL,
    ;

    companion object {
        // extractor 응답의 추출 경로(wire 문자열)를 출처로 번역한다. 모르는 값·null 은 null(출처 미기록) —
        // tolerant reader 라 extractor 가 새 경로 값을 추가해도 여기가 깨지지 않고, 기록만 비운다(계약 §2).
        fun fromWireMethod(method: String?): ItemSnapshotSource? =
            when (method) {
                "STRUCTURED" -> SERVER
                "LLM" -> SERVER_LLM
                else -> null
            }
    }
}
