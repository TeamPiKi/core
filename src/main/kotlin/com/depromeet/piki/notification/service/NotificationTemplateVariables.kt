package com.depromeet.piki.notification.service

import com.depromeet.piki.notification.domain.NotificationType

// 타입별 사용 가능한 템플릿 변수 카탈로그. 백오피스(#250) 편집 화면의 "쓸 수 있는 변수" 표시 + 검증(선언 안 된
// 변수 차단) + 미리보기 샘플값의 SSOT 다. 현재는 발송 dispatch 가 실제로 채우는 변수만 선언한다 —
// actorName·tournamentName(토너먼트), itemName(파싱 완료, #895), title/body(공지). 채우는 핸들러 resolver 와 함께 추가한다.
data class TemplateVariable(
    val name: String,
    val sample: String,
)

object NotificationTemplateVariables {
    // 토너먼트 알림 6종이 공유하는 변수 — TournamentNotificationVariables.context() 가 dispatch 시점에 채운다.
    // 카탈로그(여기)와 채우는 키가 일치해야 백오피스 검증·미리보기가 정확하다.
    private val TOURNAMENT =
        listOf(
            TemplateVariable("actorName", "홍길동"),
            TemplateVariable("tournamentName", "주말 라떼 토너먼트"),
            TemplateVariable("tournamentId", "42"),
        )

    // 삭제 알림은 어느 상품이 빠졌는지 문구에 담는다 — 공유 TOURNAMENT 변수에 itemName 을 더한다.
    private val TOURNAMENT_WITH_ITEM = TOURNAMENT + TemplateVariable("itemName", "나이키 에어맥스")

    private val catalog: Map<NotificationType, List<TemplateVariable>> =
        mapOf(
            NotificationType.TOURNAMENT_JOINED to TOURNAMENT,
            NotificationType.TOURNAMENT_ITEM_ADDED to TOURNAMENT,
            NotificationType.TOURNAMENT_ITEM_DELETED to TOURNAMENT_WITH_ITEM,
            NotificationType.TOURNAMENT_STARTED to TOURNAMENT,
            NotificationType.TOURNAMENT_PLAYED_FROM_LINK to TOURNAMENT,
            NotificationType.TOURNAMENT_COMPLETED to TOURNAMENT,
            NotificationType.TOURNAMENT_RESULT_READY to TOURNAMENT,
            // title 만 변수를 쓴다(#913) — body 는 변수 없는 고정 문구라 백오피스가 통째로 편집한다.
            NotificationType.ITEM_PARSING_COMPLETED to listOf(TemplateVariable("itemName", "나이키 에어맥스")),
            NotificationType.ITEM_PARSING_FAILED to emptyList(),
            NotificationType.ANNOUNCEMENT to
                listOf(
                    TemplateVariable("title", "피키 v1.0.1 출시"),
                    TemplateVariable("body", "새로운 토너먼트 기능을 확인해보세요"),
                ),
        )

    fun availableFor(type: NotificationType): List<TemplateVariable> = catalog[type] ?: emptyList()

    fun sampleValues(type: NotificationType): Map<String, String> = availableFor(type).associate { it.name to it.sample }

    fun names(type: NotificationType): Set<String> = availableFor(type).map { it.name }.toSet()

    // 템플릿 문자열에서 쓰인 ${변수} 이름을 뽑는다 (검증·미리보기용).
    fun usedIn(vararg templates: String): Set<String> =
        templates.flatMap { PLACEHOLDER.findAll(it).map { m -> m.groupValues[1] } }.toSet()

    private val PLACEHOLDER = Regex("""\$\{([^}]+)}""")
}
