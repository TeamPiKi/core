package com.depromeet.piki.notification.handler

import com.depromeet.piki.notification.domain.NotificationRouting
import com.depromeet.piki.tournament.repository.TournamentItemRepository
import com.depromeet.piki.wishlist.repository.WishRepository
import org.springframework.stereotype.Component
import java.util.UUID

// 아이템 파싱 완료·실패 알림의 수신자와 딥링크 라우팅 컨텍스트를 itemId 로 역조회한다. 완료/실패 핸들러가 동일 규칙이라 공유한다.
//
// "파싱 완료/실패 알림" 은 그 아이템을 올려서 결과를 기다리던 본인에게 간다 = 위시 주인 ∪ 토너먼트에 올린 본인(adder).
// 토너먼트의 다른 참가자는 추가 시점에 TOURNAMENT_ITEM_ADDED("OO 이 추가함")로 이미 알림을 받아 화면을 갱신하므로,
// 파싱완료를 또 보내지 않는다(노이즈 방지). 한 아이템이 위시·여러 토너먼트에 공유되면 각 등록자(adder)가 각자 받고,
// Set 이라 같은 유저는 1번만 받는다.
@Component
class ItemParsingRecipientResolver(
    private val wishRepository: WishRepository,
    private val tournamentItemRepository: TournamentItemRepository,
) {
    fun resolve(itemId: Long): Set<UUID> {
        val wishOwners = wishRepository.findUserIdsByItemId(itemId)
        val tournamentAdders = tournamentItemRepository.findUserIdsByItemId(itemId)
        return (wishOwners + tournamentAdders).toSet()
    }

    // 파싱 알림의 딥링크 라우팅을 itemId 로 해석한다(#408). 토너먼트 출전 행이 있으면 그 좌표(tournamentId·
    // tournamentItemId)를, 없으면 위시(/wishlist)로 본다. dispatch 는 수신자가 있을 때만 호출하므로
    // (recipients.isEmpty() early return), 이 itemId 는 위시·토너먼트 중 적어도 한쪽엔 있다 — 토너먼트가 아니면 위시다.
    // dedup 이 없어 파싱 시점 한 아이템은 단일 컨텍스트라, 라우팅은 수신자별이 아니라 아이템당 1회 해석으로 충분하다.
    fun resolveRouting(itemId: Long): NotificationRouting {
        tournamentItemRepository.findRoutingByItemId(itemId).firstOrNull()?.let {
            return NotificationRouting.Tournament(it.tournamentId, it.tournamentItemId)
        }
        return NotificationRouting.Wish
    }
}
