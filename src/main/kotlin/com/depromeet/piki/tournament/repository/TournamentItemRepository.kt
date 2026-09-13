package com.depromeet.piki.tournament.repository

import com.depromeet.piki.tournament.domain.TournamentItem
import java.util.UUID

interface TournamentItemRepository {
    fun save(item: TournamentItem): TournamentItem

    fun saveAll(items: List<TournamentItem>): List<TournamentItem>

    fun countByTournamentId(tournamentId: Long): Int

    fun findIdsByTournamentId(tournamentId: Long): List<Long>

    // 이 아이템을 토너먼트에 추가한 사람들(adder). 파싱 알림 수신자 역조회. 같은 아이템이 여러 토너먼트에 공유될 수 있다.
    // 수기 수정(pin 이동) 직렬화용 행 락 조회.
    fun findByIdForUpdate(id: Long): TournamentItem?

    fun findUserIdsBySnapshotId(snapshotId: Long): List<UUID>

    // 이 아이템의 토너먼트 출전 좌표(어느 토너먼트 / 그 안 어느 tournament_item). 파싱 알림 딥링크 라우팅 역조회(#408).
    fun findRoutingBySnapshotId(snapshotId: Long): List<TournamentItemRoutingView>

    fun findRoutingsWithUserBySnapshotId(snapshotId: Long): List<TournamentItemUserRoutingView>

    // 이 상품을 대기실(PENDING) 토너먼트에 출전시킨 카드 — 해소 통지 수신자 후보(#1028·#1051).
    fun findPendingCardsByItemId(itemId: Long): List<TournamentItemCardView>

    fun findAllByTournamentId(tournamentId: Long): List<TournamentItem>

    fun findAllByTournamentIds(ids: List<Long>): List<TournamentItem>

    fun findByIds(ids: List<Long>): List<TournamentItem>

    fun findById(id: Long): TournamentItem?

    fun softDeleteIfPending(
        id: Long,
        tournamentId: Long,
    ): Int

    fun softDeleteAllByTournamentId(tournamentId: Long)

    // 담은 사람을 옮긴다(#1081). 유니크 키가 (tournament_id, item_id)라 user_id 는 키에 없어 충돌하지 않는다.
    fun transferToUser(
        fromUserId: UUID,
        toUserId: UUID,
    ): Int
}
