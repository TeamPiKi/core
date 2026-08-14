package com.depromeet.piki.notification.domain

// 알림의 도메인 축(#473 고도화). 알림 히스토리 카드의 라벨·아이콘(위시/토너먼트)이자 클라이언트의 딥링크 분기 키다.
// - WISH: 위시로 올린 링크의 파싱 결과 — 클라는 /archive/wish 로 보낸다.
// - TOURNAMENT: 토너먼트에서 일어난 모든 일(소셜 알림 + 토너먼트에 올린 링크의 파싱 결과).
// - SYSTEM: 공지·마케팅 등 특정 도메인에 속하지 않는 전체 알림.
// 전 알림에 항상 실린다(옛 category 를 대체). 라우팅 좌표(tournamentId·tournamentItemId)가 함께 실리는지는
// kind 가 아니라 payload 셰입이 가른다 — NotificationRouting 참고.
enum class NotificationKind {
    WISH,
    TOURNAMENT,
    SYSTEM,
    ;

    companion object {
        // type + 라우팅 출처로 도메인 축을 파생한다(스키마 컬럼 없음 — 조회·직렬화 때 계산).
        // 파싱 알림(ITEM_PARSING_*)은 위시·토너먼트 양쪽에서 발행돼 type 만으론 안 갈리므로 routingKind 를 함께 본다.
        // when 이 NotificationType 전수라 else 가 없다 — 새 타입을 추가하면 여기서 컴파일이 깨져 분류를 강제한다(누락 방지).
        fun of(
            type: NotificationType,
            routingKind: NotificationKind?,
        ): NotificationKind =
            when (type) {
                NotificationType.TOURNAMENT_JOINED,
                NotificationType.TOURNAMENT_ITEM_ADDED,
                NotificationType.TOURNAMENT_ITEM_DELETED,
                NotificationType.TOURNAMENT_STARTED,
                NotificationType.TOURNAMENT_PLAYED_FROM_LINK,
                NotificationType.TOURNAMENT_COMPLETED,
                NotificationType.TOURNAMENT_RESULT_READY,
                -> TOURNAMENT

                // 파싱 알림은 라우팅 출처가 곧 도메인이다. 라우팅이 없는 경우(정상 흐름에선 resolveRouting 이 항상
                // Wish/Tournament 를 주므로 도달하지 않는다)는 위시 기본값으로 둔다 — 클라 딥링크의 기존 기본 경로와 같다.
                NotificationType.ITEM_PARSING_COMPLETED,
                NotificationType.ITEM_PARSING_INCOMPLETE,
                NotificationType.ITEM_PARSING_FAILED,
                -> routingKind ?: WISH

                NotificationType.ANNOUNCEMENT -> SYSTEM
            }
    }
}
