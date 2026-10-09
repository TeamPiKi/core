package com.depromeet.piki.tournament.repository

import com.depromeet.piki.item.domain.CanonicalInput
import com.depromeet.piki.item.domain.NormalizedInput
import com.depromeet.piki.tournament.domain.TournamentItem
import com.depromeet.piki.tournament.domain.TournamentStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime
import java.util.UUID

interface TournamentItemJpaRepository : JpaRepository<TournamentItem, Long> {
    fun countByTournamentIdAndDeletedAtIsNull(tournamentId: Long): Int

    fun findByIdAndDeletedAtIsNull(id: Long): TournamentItem?

    @Query("SELECT t FROM TournamentItem t WHERE t.tournamentId = :tournamentId AND t.deletedAt IS NULL ORDER BY t.id ASC")
    fun findAllByTournamentIdAndNotDeleted(@Param("tournamentId") tournamentId: Long): List<TournamentItem>

    @Query("SELECT t FROM TournamentItem t WHERE t.tournamentId IN :tournamentIds AND t.deletedAt IS NULL")
    fun findAllByTournamentIdInAndNotDeleted(@Param("tournamentIds") tournamentIds: List<Long>): List<TournamentItem>

    @Query("SELECT t.id FROM TournamentItem t WHERE t.tournamentId = :tournamentId AND t.deletedAt IS NULL ORDER BY t.id ASC")
    fun findIdsByTournamentId(@Param("tournamentId") tournamentId: Long): List<Long>

    // 아이템이 비어 있는 카드를 수기 수정과 파싱 성공이 동시에 확정하면 아이템이 둘 생기므로 행 락으로 직렬화한다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TournamentItem t WHERE t.id = :id AND t.deletedAt IS NULL")
    fun findByIdForUpdate(@Param("id") id: Long): TournamentItem?

    // 해소 통지(#1028) 후보. 시작한 토너먼트는 시작 시각 기준으로 값이 고정돼 채워질 카드가 없다
    @Query(
        "SELECT t.userId AS userId, t.tournamentId AS tournamentId, t.id AS tournamentItemId " +
            "FROM TournamentItem t, Tournament tour " +
            "WHERE t.itemId = :itemId AND t.deletedAt IS NULL " +
            "AND tour.id = t.tournamentId AND tour.status = :status AND tour.deletedAt IS NULL " +
            "ORDER BY t.id ASC",
    )
    fun findPendingCardsByItemId(
        @Param("itemId") itemId: Long,
        @Param("status") status: TournamentStatus = TournamentStatus.PENDING,
    ): List<TournamentItemCardView>

    // 파싱 결과는 지워진 출전에도 기록한다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TournamentItem t WHERE t.id = :id")
    fun findByIdIncludingDeletedForUpdate(@Param("id") id: Long): TournamentItem?

    @Query("SELECT t FROM TournamentItem t WHERE t.id = :id")
    fun findByIdIncludingDeleted(@Param("id") id: Long): TournamentItem?

    // 같은 입력으로 파싱을 요청한 적 있는 출전. 한 토너먼트의 출전으로 먼저 좁혀져 입력 인덱스가 필요 없다
    @Query(
        "SELECT t.id FROM TournamentItem t, ItemParseOutbox o " +
            "WHERE o.slotType = com.depromeet.piki.item.domain.ItemSlotType.TOURNAMENT_ITEM AND o.slotId = t.id " +
            "AND t.tournamentId = :tournamentId AND t.deletedAt IS NULL AND o.normalizedInput = :value",
    )
    fun findIdsByTournamentIdAndNormalizedInput(
        @Param("tournamentId") tournamentId: Long,
        @Param("value") normalized: NormalizedInput,
    ): List<Long>

    @Query(
        "SELECT t.id FROM TournamentItem t, Item i WHERE i.id = t.itemId AND i.canonicalUrl = :canonical " +
            "AND t.tournamentId = :tournamentId AND t.deletedAt IS NULL AND i.deletedAt IS NULL",
    )
    fun findIdsByTournamentIdAndItemCanonical(
        @Param("tournamentId") tournamentId: Long,
        @Param("canonical") canonical: CanonicalInput,
    ): List<Long>

    @Query(
        "SELECT o.normalizedInput FROM TournamentItem t, ItemParseOutbox o " +
            "WHERE o.slotType = com.depromeet.piki.item.domain.ItemSlotType.TOURNAMENT_ITEM AND o.slotId = t.id " +
            "AND t.tournamentId = :tournamentId AND t.deletedAt IS NULL AND o.normalizedInput IN :values",
    )
    fun findRequestedInputsByTournamentId(
        @Param("tournamentId") tournamentId: Long,
        @Param("values") values: Collection<String>,
    ): List<String>

    @Modifying
    @Query(
        "UPDATE TournamentItem t SET t.deletedAt = :now WHERE t.id = :id AND t.tournamentId = :tournamentId " +
            "AND t.deletedAt IS NULL " +
            "AND EXISTS (SELECT 1 FROM Tournament tour WHERE tour.id = :tournamentId AND tour.status = :status AND tour.deletedAt IS NULL)",
    )
    fun softDeleteIfPending(
        @Param("id") id: Long,
        @Param("tournamentId") tournamentId: Long,
        @Param("status") status: TournamentStatus = TournamentStatus.PENDING,
        @Param("now") now: LocalDateTime,
    ): Int

    @Modifying
    @Query("UPDATE TournamentItem t SET t.deletedAt = :now WHERE t.tournamentId = :tournamentId AND t.deletedAt IS NULL")
    fun softDeleteAllByTournamentId(
        @Param("tournamentId") tournamentId: Long,
        @Param("now") now: LocalDateTime,
    )

    // 담은 사람만 갈아끼운다(#1081). 유니크 키가 (tournament_id, item_id)라 user_id 는 키에 없어 충돌하지 않는다.
    // 참여가 회원에게 넘어간 방으로 범위를 좁힌다 — 넘어가지 않은 방까지 옮기면 그 방에 없는 사람이 아이템 주인이 된다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE TournamentItem t SET t.userId = :toUserId, t.updatedAt = :now " +
            "WHERE t.userId = :fromUserId AND t.tournamentId IN :tournamentIds AND t.deletedAt IS NULL",
    )
    fun transferToUser(
        @Param("fromUserId") fromUserId: UUID,
        @Param("toUserId") toUserId: UUID,
        @Param("tournamentIds") tournamentIds: Collection<Long>,
        @Param("now") now: LocalDateTime,
    ): Int
}
