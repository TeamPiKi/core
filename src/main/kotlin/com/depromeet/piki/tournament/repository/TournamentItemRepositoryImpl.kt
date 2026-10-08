package com.depromeet.piki.tournament.repository

import com.depromeet.piki.item.domain.NormalizedInput
import com.depromeet.piki.tournament.domain.TournamentItem
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.UUID

@Repository
class TournamentItemRepositoryImpl(
    private val tournamentItemJpaRepository: TournamentItemJpaRepository,
) : TournamentItemRepository {
    override fun save(item: TournamentItem): TournamentItem = tournamentItemJpaRepository.save(item)

    override fun saveAll(items: List<TournamentItem>): List<TournamentItem> = tournamentItemJpaRepository.saveAll(items)

    override fun countByTournamentId(tournamentId: Long): Int =
        tournamentItemJpaRepository.countByTournamentIdAndDeletedAtIsNull(tournamentId)

    override fun findIdsByTournamentId(tournamentId: Long): List<Long> =
        tournamentItemJpaRepository.findIdsByTournamentId(tournamentId)

    override fun findByIdForUpdate(id: Long): TournamentItem? = tournamentItemJpaRepository.findByIdForUpdate(id)

    override fun findByIdIncludingDeletedForUpdate(id: Long): TournamentItem? =
        tournamentItemJpaRepository.findByIdIncludingDeletedForUpdate(id)

    override fun findByIdIncludingDeleted(id: Long): TournamentItem? = tournamentItemJpaRepository.findByIdIncludingDeleted(id)

    override fun findIdByTournamentIdAndNormalizedInput(
        tournamentId: Long,
        normalized: NormalizedInput,
    ): Long? = tournamentItemJpaRepository.findIdsByTournamentIdAndNormalizedInput(tournamentId, normalized).firstOrNull()

    override fun findRequestedInputs(
        tournamentId: Long,
        values: Collection<String>,
    ): Set<String> =
        if (values.isEmpty()) emptySet() else tournamentItemJpaRepository.findRequestedInputsByTournamentId(tournamentId, values).toSet()

    override fun findPendingCardsByItemId(itemId: Long): List<TournamentItemCardView> =
        tournamentItemJpaRepository.findPendingCardsByItemId(itemId)

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

    override fun transferToUser(
        fromUserId: UUID,
        toUserId: UUID,
        tournamentIds: List<Long>,
    ): Int =
        if (tournamentIds.isEmpty()) {
            0
        } else {
            tournamentItemJpaRepository.transferToUser(fromUserId, toUserId, tournamentIds, LocalDateTime.now())
        }
}
