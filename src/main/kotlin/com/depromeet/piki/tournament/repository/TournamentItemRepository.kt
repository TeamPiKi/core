package com.depromeet.piki.tournament.repository

import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.tournament.domain.TournamentItem
import java.util.UUID

interface TournamentItemRepository {
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

    // 이 아이템의 지정 상태 버전을 pin 한 출전의 (등록자, 토너먼트 좌표) — 해소 통지 수신자 역조회(#1028).
    fun findRoutingsWithUserByItemIdAndStatuses(
        itemId: Long,
        statuses: Collection<ItemStatus>,
    ): List<TournamentItemUserRoutingView>

    fun findAllByTournamentId(tournamentId: Long): List<TournamentItem>

    fun findAllByTournamentIds(ids: List<Long>): List<TournamentItem>

    fun findByIds(ids: List<Long>): List<TournamentItem>

    fun findById(id: Long): TournamentItem?

    fun softDeleteIfPending(
        id: Long,
        tournamentId: Long,
    ): Int

    fun softDeleteAllByTournamentId(tournamentId: Long)
}
