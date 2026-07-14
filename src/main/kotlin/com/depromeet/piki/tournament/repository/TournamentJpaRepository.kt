package com.depromeet.piki.tournament.repository

import com.depromeet.piki.tournament.domain.Tournament
import com.depromeet.piki.tournament.domain.TournamentStatus
import jakarta.persistence.LockModeType
import java.time.LocalDateTime
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TournamentJpaRepository : JpaRepository<Tournament, Long> {
    fun findByIdAndDeletedAtIsNull(id: Long): Tournament?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Tournament t WHERE t.id = :id AND t.deletedAt IS NULL")
    fun findByIdForUpdate(id: Long): Tournament?

    fun findByIdInAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(
        ids: List<Long>,
        statuses: List<TournamentStatus>,
    ): List<Tournament>

    fun findByIdInAndDeletedAtIsNullOrderByCreatedAtDesc(ids: List<Long>): List<Tournament>

    fun findBySourceTournamentIdAndDeletedAtIsNull(sourceTournamentId: Long): List<Tournament>

    // 활성 초대코드 조회는 base 컬럼 invite_code 가 아니라 generated 컬럼 active_invite_code 로 한다.
    // uk_tournaments_active_invite_code 유니크 인덱스가 이 컬럼에만 걸려 있어, invite_code 로 조회하면
    // MySQL 8 이 인덱스를 못 써 tournaments 풀스캔이 된다. 삭제행은 active_invite_code 가 NULL 이라
    // deleted_at IS NULL 조건도 자연 흡수된다.
    // findBy 는 결과가 2개 이상이면 IncorrectResultSizeDataAccessException → 500 이므로 findFirst 로
    // 방어한다. 유니크 인덱스가 정상이면 활성 코드 중복은 없지만 레거시 데이터 등 예외 상황에서도 안전하다.
    fun findFirstByActiveInviteCode(activeInviteCode: String): Tournament?

    fun existsByActiveInviteCode(activeInviteCode: String): Boolean

    @Modifying
    @Query("UPDATE Tournament t SET t.deletedAt = :now WHERE t.id = :id AND t.deletedAt IS NULL")
    fun softDeleteById(@Param("id") id: Long, @Param("now") now: LocalDateTime)
}
