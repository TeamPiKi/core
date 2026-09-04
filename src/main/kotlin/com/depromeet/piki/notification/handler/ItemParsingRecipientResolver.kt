package com.depromeet.piki.notification.handler

import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.notification.domain.NotificationRouting
import com.depromeet.piki.tournament.repository.TournamentItemRepository
import com.depromeet.piki.tournament.repository.TournamentItemUserRoutingView
import com.depromeet.piki.wishlist.repository.WishOwnerView
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

    // 파싱 알림의 딥링크 라우팅을 **수신자별로** 배치 해석한다(#933·#408·#576). 위시 주인 → Wish(그 유저의 wishId),
    // 토너먼트 등록자 → 자기 Tournament(tournamentId, tournamentItemId). 한 유저가 양쪽이면 WISH 우선 — 파싱은
    // 결국 그 사람 위시의 결과이고, 토너먼트 아이템은 토너먼트에서 도달 가능해 중복이 적다.
    // 조회는 2회(위시·토너먼트)로 고정 — 수신자 수만큼 늘지 않는다(N+1 방지). 공유(#825)로 한 유저가 같은 버전을
    // 여러 토너먼트에 올렸으면 id 오름차순 첫 좌표를 골라 결정성만 확보한다(카드 갱신은 SSE 전 좌표 브로드캐스트가 진다).
    // dispatch 는 수신자가 있을 때만 호출하므로 각 수신자는 위시·토너먼트 중 적어도 한쪽에 있어 맵에 반드시 담긴다.
    fun resolveRoutingsBySnapshot(snapshotId: Long): Map<UUID, NotificationRouting> =
        routingsOf(
            wishRepository.findOwnerWishIdsBySnapshotId(snapshotId),
            tournamentItemRepository.findRoutingsWithUserBySnapshotId(snapshotId),
        )

    // 해소 통지(#1028)의 수신자와 라우팅. 위와 달리 **아이템** 으로 찾고 미완성 상태만 고른다 — 이 알림이 가는 곳은
    // 방금 성공한 버전이 아니라 다른 미완성 버전에 멈춰 있던 사람이라, 버전 역조회로는 한 명도 안 잡힌다.
    //
    // 두 알림의 수신자가 배타적인 것도 여기서 나온다: 성공한 버전을 가리키면 READY 라 이 조회에 안 걸리고,
    // 다른 미완성 버전을 가리키면 완료 알림의 버전 역조회에 안 걸린다. 본인이 새로고침해 성공한 경우는 포인터가
    // 새 버전으로 옮겨 가 있어 자동으로 완료 알림 쪽이 된다 — "남 때문에" 를 판정하는 별도 플래그가 필요 없다.
    fun resolveRecoveredRoutingsByItem(itemId: Long): Map<UUID, NotificationRouting> =
        routingsOf(
            wishRepository.findOwnerWishIdsByItemIdAndStatuses(itemId, UNRESOLVED_STATUSES),
            tournamentItemRepository.findRoutingsWithUserByItemIdAndStatuses(itemId, UNRESOLVED_STATUSES),
        )

    // 위시 좌표 ∪ 토너먼트 좌표를 수신자별 라우팅 하나로 접는다. 한 유저가 양쪽이면 WISH 우선(위 규칙),
    // 같은 유저의 토너먼트 좌표가 여럿이면 id 오름차순 첫 행(쿼리의 ORDER BY)으로 결정성만 확보한다.
    private fun routingsOf(
        wishOwners: List<WishOwnerView>,
        tournamentRoutings: List<TournamentItemUserRoutingView>,
    ): Map<UUID, NotificationRouting> {
        val wishIdByUser = wishOwners.associate { it.userId to it.wishId }
        val tournamentByUser = tournamentRoutings.groupBy { it.userId }.mapValues { (_, rows) -> rows.first() }
        return (wishIdByUser.keys + tournamentByUser.keys).associateWith { userId ->
            wishIdByUser[userId]?.let { NotificationRouting.Wish(it) }
                ?: tournamentByUser.getValue(userId).let { NotificationRouting.Tournament(it.tournamentId, it.tournamentItemId) }
        }
    }

    companion object {
        // "아직 사람 손이 필요한 상태" — 이 버전을 가리키고 있으면 카드가 비어 있다. 진행 중(PENDING·PROCESSING)은
        // 곧 자기 결과 알림을 받으므로 제외하고, 옛 READY 를 가리키는 사람도 이미 값을 보고 있어 제외된다.
        private val UNRESOLVED_STATUSES = listOf(ItemStatus.FAILED, ItemStatus.INCOMPLETE)
    }
}
