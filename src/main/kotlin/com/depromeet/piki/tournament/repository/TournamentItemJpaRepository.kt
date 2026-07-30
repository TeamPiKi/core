package com.depromeet.piki.tournament.repository

import com.depromeet.piki.tournament.domain.TournamentItem
import com.depromeet.piki.tournament.domain.TournamentStatus
import java.time.LocalDateTime
import java.util.UUID
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TournamentItemJpaRepository : JpaRepository<TournamentItem, Long> {
    fun countByTournamentIdAndDeletedAtIsNull(tournamentId: Long): Int

    fun findByIdAndDeletedAtIsNull(id: Long): TournamentItem?

    @Query("SELECT t FROM TournamentItem t WHERE t.tournamentId = :tournamentId AND t.deletedAt IS NULL ORDER BY t.id ASC")
    fun findAllByTournamentIdAndNotDeleted(@Param("tournamentId") tournamentId: Long): List<TournamentItem>

    @Query("SELECT t FROM TournamentItem t WHERE t.tournamentId IN :tournamentIds AND t.deletedAt IS NULL")
    fun findAllByTournamentIdInAndNotDeleted(@Param("tournamentIds") tournamentIds: List<Long>): List<TournamentItem>

    @Query("SELECT t.id FROM TournamentItem t WHERE t.tournamentId = :tournamentId AND t.deletedAt IS NULL ORDER BY t.id ASC")
    fun findIdsByTournamentId(@Param("tournamentId") tournamentId: Long): List<Long>

    // 수기 수정의 조회→검사→새 버전 저장→pin 이동 흐름을 행 락으로 직렬화한다(WishJpaRepository.findByIdForUpdate 와 같은 결).
    // 락 없이는 동시 수정 두 건이 같은 pin 을 읽고 나중 커밋이 먼저 만든 MANUAL 버전 포인터를 덮어써 유령 버전이 남는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TournamentItem t WHERE t.id = :id AND t.deletedAt IS NULL")
    fun findByIdForUpdate(@Param("id") id: Long): TournamentItem?

    // 이 버전(snapshot)을 pin 해 올린 사람들(adder). 파싱 알림 수신자 역조회 — 파싱 사실의 주체가 버전이라
    // 버전으로 직결한다(#576): 같은 item 의 다른 버전을 pin 한 출전(과거 버전·갱신 전 버전)은 이 파싱의 수신자가 아니다.
    // 공유(#825)로 한 버전이 여러 출전에 pin 되면 각 adder 가 받는다(DISTINCT).
    @Query(
        "SELECT DISTINCT t.userId FROM TournamentItem t " +
            "WHERE t.snapshotId = :snapshotId AND t.deletedAt IS NULL",
    )
    fun findUserIdsBySnapshotId(@Param("snapshotId") snapshotId: Long): List<UUID>

    // 이 버전(snapshot)을 pin 한 토너먼트 출전 좌표(tournamentId·tournament_item id). 파싱 알림 딥링크·SSE 카드
    // 라우팅 역조회(#408·#576). 버전 직결이라 이벤트 status 가 어느 좌표에서든 참이다 — itemId 라우팅 시절의
    // spurious 갱신(다른 버전 카드에 남의 완료·실패가 전파)이 구조적으로 사라진다. id 오름차순은 결정성용.
    @Query(
        "SELECT t.tournamentId AS tournamentId, t.id AS tournamentItemId FROM TournamentItem t " +
            "WHERE t.snapshotId = :snapshotId AND t.deletedAt IS NULL ORDER BY t.id ASC",
    )
    fun findRoutingBySnapshotId(@Param("snapshotId") snapshotId: Long): List<TournamentItemRoutingView>

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
}
