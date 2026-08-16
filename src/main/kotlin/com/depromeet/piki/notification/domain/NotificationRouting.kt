package com.depromeet.piki.notification.domain

// 알림의 딥링크 라우팅 컨텍스트 — 서버는 완성 URL 을 박지 않고 도메인 식별자만 내려, 클라이언트가 URL 을 조립한다.
// routingKind 가 출처를 가르고, 각 출처가 딥링크에 필요한 식별자를 함께 싣는다.
// TOURNAMENT 는 "어느 토너먼트(tournamentId) / 그 안 어느 아이템(tournamentItemId)" 2좌표를 싣는다
// (클라가 토너먼트로 입장한 뒤 그 아이템을 지목하려면 둘 다 필요하다).
// WISH 는 위시 상세(GET /api/v1/wishlists/{wishId})로 가는 wishId 를 싣는다(#933). wishId 는 수신자마다 다르므로
// 수신자별 라우팅 해석에서 채워진다 — 이벤트 단위로 공유하면 남의 위시 id 가 박힌다. 과거 알림(컬럼 도입 전)은
// 값이 없어 nullable 이고, 그 경우 클라는 refId(itemId)로 역추적하는 기존 폴백을 쓴다.
//
// 이 축은 응답 필드 kind(NotificationKind, 전 알림에 실리는 3값 도메인 축)와 다르다 — 여기 routingKind 는
// "딥링크 좌표가 어느 셰입인가" 만 가리키므로 SYSTEM 이 올 수 없다. 응답 kind 는 이 값을 입력으로 파생된다.
sealed interface NotificationRouting {
    val routingKind: NotificationKind

    data class Wish(
        // 위시 상세 딥링크 대상. 수신자별로 다르며, 과거 행(컬럼 도입 전)은 null.
        val wishId: Long?,
    ) : NotificationRouting {
        override val routingKind: NotificationKind = NotificationKind.WISH
    }

    data class Tournament(
        val tournamentId: Long,
        val tournamentItemId: Long,
    ) : NotificationRouting {
        override val routingKind: NotificationKind = NotificationKind.TOURNAMENT
    }
}
