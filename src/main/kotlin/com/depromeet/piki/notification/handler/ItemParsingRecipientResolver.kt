package com.depromeet.piki.notification.handler

import com.depromeet.piki.notification.domain.NotificationRouting
import com.depromeet.piki.tournament.repository.TournamentItemRepository
import com.depromeet.piki.wishlist.repository.WishRepository
import org.springframework.stereotype.Component
import java.util.UUID

// 아이템 파싱 완료·실패 알림의 수신자와 딥링크 라우팅 컨텍스트를 **snapshotId(버전)** 로 역조회한다(#576).
// 완료/실패 핸들러가 동일 규칙이라 공유한다.
//
// "파싱 완료/실패 알림" 은 그 버전의 결과를 기다리던 본인에게 간다 = 그 버전을 활성으로 가리키는 위시 주인 ∪
// 그 버전을 pin 해 올린 adder. itemId 가 아니라 버전인 이유: 한 item 에 여러 버전이 공존(갱신)하고 공유(#825)로
// 버전이 여러 곳에 pin 될 수 있어, item 단위 역조회는 "이 파싱과 무관한 옛 버전을 보는 사람"까지 끌어들인다 —
// 위시 갱신 파싱의 알림이 그 item 이 출전했던 토너먼트 딥링크로 새던 기존 라우팅 버그도 이 전환으로 함께 사라진다.
// 토너먼트의 다른 참가자는 추가 시점에 TOURNAMENT_ITEM_ADDED 로 이미 갱신하므로 파싱완료를 또 보내지 않는다(노이즈 방지).
// Set 이라 같은 유저는 1번만 받는다.
@Component
class ItemParsingRecipientResolver(
    private val wishRepository: WishRepository,
    private val tournamentItemRepository: TournamentItemRepository,
) {
    fun resolve(snapshotId: Long): Set<UUID> {
        val wishOwners = wishRepository.findUserIdsBySnapshotId(snapshotId)
        val tournamentAdders = tournamentItemRepository.findUserIdsBySnapshotId(snapshotId)
        return (wishOwners + tournamentAdders).toSet()
    }

    // 파싱 알림의 딥링크 라우팅을 snapshotId 로 해석한다(#408·#576). 그 버전을 pin 한 출전이 있으면 그 좌표를,
    // 없으면 위시(/archive/wish)로 본다. dispatch 는 수신자가 있을 때만 호출하므로(recipients.isEmpty() early return)
    // 이 버전은 위시·토너먼트 중 적어도 한쪽에 있다 — 출전 pin 이 아니면 위시다.
    // 공유(#825)로 한 버전이 여러 출전에 pin 되면 firstOrNull(id 오름차순)이 좌표를 하나만 고른다 — 알림 라우팅은
    // 딥링크 하나라 결정성만 있으면 되고, 카드 갱신은 SSE(전 좌표 브로드캐스트)가 진다.
    fun resolveRouting(snapshotId: Long): NotificationRouting {
        tournamentItemRepository.findRoutingBySnapshotId(snapshotId).firstOrNull()?.let {
            return NotificationRouting.Tournament(it.tournamentId, it.tournamentItemId)
        }
        // wishId 는 수신자별이라 이 이벤트-단위 해석에선 채우지 못한다(null). 수신자별 라우팅(#933, resolveRecipientContexts)이
        // 이 메서드를 대체하며 각 수신자의 wishId 를 채운다 — 이 메서드는 그 전환의 과도기 형태다.
        return NotificationRouting.Wish(null)
    }
}
