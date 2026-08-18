package com.depromeet.piki.tournament.repository

import com.depromeet.piki.tournament.domain.TournamentItem
import java.time.LocalDateTime
import java.util.UUID
import org.springframework.stereotype.Repository

@Repository
class TournamentItemRepositoryImpl(
    private val tournamentItemJpaRepository: TournamentItemJpaRepository,
) : TournamentItemRepository {
    override fun saveAll(items: List<TournamentItem>): List<TournamentItem> = tournamentItemJpaRepository.saveAll(items)

    override fun countByTournamentId(tournamentId: Long): Int =
        tournamentItemJpaRepository.countByTournamentIdAndDeletedAtIsNull(tournamentId)

    override fun findIdsByTournamentId(tournamentId: Long): List<Long> =
        tournamentItemJpaRepository.findIdsByTournamentId(tournamentId)

    override fun findByIdForUpdate(id: Long): TournamentItem? = tournamentItemJpaRepository.findByIdForUpdate(id)

    override fun findUserIdsBySnapshotId(snapshotId: Long): List<UUID> =
        tournamentItemJpaRepository.findUserIdsBySnapshotId(snapshotId)

    override fun findRoutingBySnapshotId(snapshotId: Long): List<TournamentItemRoutingView> =
        tournamentItemJpaRepository.findRoutingBySnapshotId(snapshotId)

    override fun findRoutingsWithUserBySnapshotId(snapshotId: Long): List<TournamentItemUserRoutingView> =
        tournamentItemJpaRepository.findRoutingsWithUserBySnapshotId(snapshotId)

    override fun findAllByTournamentId(tournamentId: Long): List<TournamentItem> =
        tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId)

    override fun findAllByTournamentIds(ids: List<Long>): List<TournamentItem> =
        if (ids.isEmpty()) emptyList()
        else tournamentItemJpaRepository.findAllByTournamentIdInAndNotDeleted(ids)

    override fun findByIds(ids: List<Long>): List<TournamentItem> = tournamentItemJpaRepository.findAllById(ids)

    override fun findById(id: Long): TournamentItem? = tournamentItemJpaRepository.findByIdAndDeletedAtIsNull(id)

    override fun softDeleteIfPending(
        id: Long,
        tournamentId: Long,
    ): Int = tournamentItemJpaRepository.softDeleteIfPending(id, tournamentId, now = LocalDateTime.now())

    override fun softDeleteAllByTournamentId(tournamentId: Long) {
        tournamentItemJpaRepository.softDeleteAllByTournamentId(tournamentId, LocalDateTime.now())
    }
}
