package com.depromeet.piki.notification.domain

// 알림의 딥링크 라우팅 컨텍스트 — 서버는 완성 URL 을 박지 않고 도메인 식별자만 내려, 클라이언트가 URL 을 조립한다.
// routingKind 가 출처를 가르고, TOURNAMENT 는 "어느 토너먼트(tournamentId) / 그 안 어느 아이템(tournamentItemId)" 2좌표를 함께 싣는다
// (클라가 토너먼트로 입장한 뒤 그 아이템을 지목하려면 둘 다 필요하다). WISH 는 /archive/wish 라 식별자가 없다 —
// "WISH 엔 식별자가 없고 TOURNAMENT 만 두 식별자를 가진다" 는 불변식을 타입으로 강제하려고 sealed 로 둔다.
//
// 이 축은 응답 필드 kind(NotificationKind, 전 알림에 실리는 3값 도메인 축)와 다르다 — 여기 routingKind 는
// "딥링크 좌표가 어느 셰입인가" 만 가리키므로 SYSTEM 이 올 수 없다. 응답 kind 는 이 값을 입력으로 파생된다.
sealed interface NotificationRouting {
    val routingKind: NotificationKind

    data object Wish : NotificationRouting {
        override val routingKind: NotificationKind = NotificationKind.WISH
    }

    data class Tournament(
        val tournamentId: Long,
        val tournamentItemId: Long,
    ) : NotificationRouting {
        override val routingKind: NotificationKind = NotificationKind.TOURNAMENT
    }
}
