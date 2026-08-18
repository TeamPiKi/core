package com.depromeet.piki.notification.controller.dto

import com.depromeet.piki.notification.domain.Notification
import com.depromeet.piki.notification.domain.NotificationKind
import com.depromeet.piki.notification.domain.NotificationRouting
import com.depromeet.piki.notification.domain.NotificationType
import java.time.LocalDateTime

// SSE 이벤트(name=notification)의 data 로 직렬화되는 payload.
// 알림 종류별로 셰입이 다르다 — 라우팅 컨텍스트(#408)가 없는 알림은 refId 만, 토너먼트 아이템을 지목하는 알림은 좌표 2개를 더 싣는다.
// nullable 잡탕 + NON_NULL 로 런타임에 가리는 대신, sealed 로 각 셰입을 타입에 고정한다(도메인 NotificationRouting 과 같은 결).
// 클라이언트는 type 으로 화면을, kind 로 라벨·아이콘과 딥링크 출처를 분기한다. id 는 읽음 처리(#246)의 키다.
//
// kind 는 전 셰입 공통이다 — 모든 알림에 항상 실린다(옛 category 를 대체). type 과 라우팅 출처(routingKind)에서
// 파생하며(NotificationKind.of) 스키마 컬럼은 없다.
sealed interface NotificationSsePayload {
    val id: Long
    val type: NotificationType
    val kind: NotificationKind
    val title: String
    val body: String
    val refId: Long
    val isRead: Boolean
    val createdAt: LocalDateTime

    // 라우팅 컨텍스트가 없는 알림(토너먼트 소셜 알림·공지 등). refId 만으로 딥링크가 결정된다(예: refId=tournamentId).
    data class Reference(
        override val id: Long,
        override val type: NotificationType,
        override val kind: NotificationKind,
        override val title: String,
        override val body: String,
        override val refId: Long,
        override val isRead: Boolean,
        override val createdAt: LocalDateTime,
    ) : NotificationSsePayload

    // 위시 출처 파싱 알림. refId(=itemId) + kind=WISH + wishId(#933). 클라는 wishId 로 위시 상세
    // (GET /api/v1/wishlists/{wishId})로 딥링크한다. wishId 는 수신자별로 다르며, 컬럼 도입 전 과거 행은 null 이라
    // 그 경우 클라가 refId(itemId)로 역추적하는 기존 폴백을 쓴다.
    data class WishParsing(
        override val id: Long,
        override val type: NotificationType,
        override val kind: NotificationKind,
        override val title: String,
        override val body: String,
        override val refId: Long,
        override val isRead: Boolean,
        override val createdAt: LocalDateTime,
        val wishId: Long?,
    ) : NotificationSsePayload

    // 토너먼트 아이템을 지목하는 알림. 입장(tournamentId)·아이템 지목(tournamentItemId) 좌표를 싣는다.
    // 두 용도가 공유한다: 파싱 알림(refId=itemId) · 아이템 삭제 알림(refId=tournamentId). refId 의미는 type 별로 갈리니
    // 클라는 type 으로 먼저 분기한다. (파싱 전용이 아니라 "토너먼트 아이템 라우팅" 셰입이라 이름이 중립적이다.)
    data class TournamentRouted(
        override val id: Long,
        override val type: NotificationType,
        override val kind: NotificationKind,
        override val title: String,
        override val body: String,
        override val refId: Long,
        override val isRead: Boolean,
        override val createdAt: LocalDateTime,
        val tournamentId: Long,
        val tournamentItemId: Long,
    ) : NotificationSsePayload

    companion object {
        // 채널에 도달한 Notification 은 dispatcher 가 이미 저장한 영속 엔티티라 id 가 보장된다(getId()).
        // 라우팅 컨텍스트(routing())로 셰입을 가르고, kind 는 엔티티가 파생해 준다(domainKind()).
        fun from(notification: Notification): NotificationSsePayload {
            val id = notification.getId()
            val kind = notification.domainKind()
            return when (val routing = notification.routing()) {
                null ->
                    Reference(
                        id = id,
                        type = notification.type,
                        kind = kind,
                        title = notification.title,
                        body = notification.body,
                        refId = notification.refId,
                        isRead = notification.isRead,
                        createdAt = notification.createdAt,
                    )

                is NotificationRouting.Wish ->
                    WishParsing(
                        id = id,
                        type = notification.type,
                        kind = kind,
                        title = notification.title,
                        body = notification.body,
                        refId = notification.refId,
                        isRead = notification.isRead,
                        createdAt = notification.createdAt,
                        wishId = routing.wishId,
                    )

                is NotificationRouting.Tournament ->
                    TournamentRouted(
                        id = id,
                        type = notification.type,
                        kind = kind,
                        title = notification.title,
                        body = notification.body,
                        refId = notification.refId,
                        isRead = notification.isRead,
                        createdAt = notification.createdAt,
                        tournamentId = routing.tournamentId,
                        tournamentItemId = routing.tournamentItemId,
                    )
            }
        }
    }
}
