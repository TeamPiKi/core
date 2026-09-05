package com.depromeet.piki.notification.service

import com.depromeet.piki.notification.domain.NotificationType

// 타입별 사용 가능한 템플릿 변수 카탈로그. 백오피스(#250) 편집 화면의 "쓸 수 있는 변수" 표시 + 검증(선언 안 된
// 변수 차단) + 미리보기 샘플값의 SSOT 다. 현재는 발송 dispatch 가 실제로 채우는 변수만 선언한다 —
// actorName·tournamentName(토너먼트), itemName(파싱 완료·미완·실패), title/body(공지). 채우는 핸들러 resolver 와 함께 추가한다.
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

    // 파싱 알림 3종(완료·미완·실패)이 공유하는 title 변수 — 각 핸들러의 resolveActorContext 가 snapshot 이름을
    // 역조회해 dispatch 시점에 채운다(ItemDisplayName 기본값 "상품" 폴백). 셋 다 title=이름 이라 OS 푸시 절단에도
    // 무슨 아이템인지가 남는다(#913).
    private val ITEM_NAME = TemplateVariable("itemName", "나이키 에어맥스")

    private val catalog: Map<NotificationType, List<TemplateVariable>> =
        mapOf(
            NotificationType.TOURNAMENT_JOINED to TOURNAMENT,
            NotificationType.TOURNAMENT_ITEM_ADDED to TOURNAMENT,
            NotificationType.TOURNAMENT_ITEM_DELETED to TOURNAMENT_WITH_ITEM,
            NotificationType.TOURNAMENT_STARTED to TOURNAMENT,
            NotificationType.TOURNAMENT_PLAYED_FROM_LINK to TOURNAMENT,
            NotificationType.TOURNAMENT_COMPLETED to TOURNAMENT,
            NotificationType.TOURNAMENT_RESULT_READY to TOURNAMENT,
            // title=itemName, body=completionMessage 둘 다 변수다(#933). completionMessage 는 출처별 완료 문구
            // (위시/토너먼트)라 dispatch 가 수신자별로 채우고 코드(ItemParsingCompletedHandler)가 문구를 소유한다 —
            // body 는 백오피스 편집 대상이 아니지만, 카탈로그엔 선언해야 템플릿 검증(선언 안 된 변수 차단)이 통과한다.
            NotificationType.ITEM_PARSING_COMPLETED to
                listOf(
                    ITEM_NAME,
                    TemplateVariable("completionMessage", "위시 저장이 성공했어요"),
                ),
            // 미완 알림도 title=${itemName} 이고 핸들러가 채운다(#944). 선언이 빠져 있어 어드민에서 이 템플릿을
            // 저장하면 "선언 안 된 변수" 검증에 걸려 저장 자체가 막혔다 — itemName 을 선언해 정합을 맞춘다.
            NotificationType.ITEM_PARSING_INCOMPLETE to listOf(ITEM_NAME),
            // 실패 알림은 현재 고정 문구라 ${itemName} 을 안 쓰지만, 핸들러가 itemName 을 채우므로(다른 파싱 알림과
            // 동일) 선언해 둔다 — 어드민이 실패 문구에도 아이템 이름을 넣어 편집할 수 있다.
            NotificationType.ITEM_PARSING_FAILED to listOf(ITEM_NAME),
            // 해소 통지(#1028)도 title=${itemName} 이고 핸들러가 같은 방식으로 채운다 — 다만 이름의 출처는
            // 방금 성공한 버전이라, 실패로 비어 있던 이름 대신 실제 상품명이 제목에 뜬다.
            NotificationType.ITEM_PARSING_RECOVERED to listOf(ITEM_NAME),
            // 새로고침 완료(#1036)도 title=${itemName} 이고 핸들러가 새 성공본의 이름으로 채운다.
            NotificationType.ITEM_REFRESH_COMPLETED to listOf(ITEM_NAME),
            // 새로고침 실패는 변수를 채우지 않는다 — 실패한 버전은 이름이 비어 itemName 이 늘 기본값이라 쓸모가 없고,
            // 카탈로그는 dispatch 가 실제로 채우는 변수만 선언한다. 문구는 title·body 모두 고정이다.
            NotificationType.ITEM_REFRESH_FAILED to emptyList(),
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
