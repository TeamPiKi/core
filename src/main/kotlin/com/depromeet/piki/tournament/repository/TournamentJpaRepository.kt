package com.depromeet.piki.tournament.repository

import com.depromeet.piki.tournament.domain.PlayType
import com.depromeet.piki.tournament.domain.Tournament
import com.depromeet.piki.tournament.domain.TournamentStatus
import jakarta.persistence.LockModeType
import java.time.LocalDateTime
import java.util.UUID
import org.springframework.data.domain.Pageable
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

    // 목록 화면 쿼리 — 내 tournament_user 멤버십과 조인해 가시성 필터(ROOT 는 소유자·PENDING 만)까지 DB 에서 끝낸다.
    // 필터·정렬·limit 이 앱으로 올라오면 홈 카드(limit=3) 한 번에 내 전체 이력의 참가자·프로필을 선로드하게 된다.
    // uk_tournament_users (tournament_id, user_id) 가 (유저, 토너먼트) 당 멤버십 행을 1개로 보장해 조인이 행을 늘리지 않는다.
    // createdAt 동률 시 어느 행이 LIMIT 에 잘릴지 비결정적이므로 생성 순서와 일치하는 id 로 tie-break 한다.
    // t._ownerTournamentUserId — 엔티티가 backing field 캡슐화(private var _ownerTournamentUserId)라 JPA 속성명이 field 이름이다.
    // playType 파생 판정(#836): CLONE 은 SOCIAL, ROOT 는 참여자 2명 이상이면 SOCIAL·나 혼자면 SOLO.
    // 참여자 수는 tournament_user 행 수 — 현재 유저 조인 tu 와 별개인 tu2 서브쿼리로 전체 참여자를 센다.
    @Query(
        """
        SELECT t FROM Tournament t
        JOIN TournamentUser tu ON tu.tournamentId = t.id
        WHERE tu.userId = :userId
          AND tu.deletedAt IS NULL
          AND t.deletedAt IS NULL
          AND t.status IN :statuses
          AND (
            t.sourceTournamentId IS NOT NULL
            OR t._ownerTournamentUserId = tu.id
            OR t.status = com.depromeet.piki.tournament.domain.TournamentStatus.PENDING
          )
          AND (
            :playType IS NULL
            OR (
              :playType = com.depromeet.piki.tournament.domain.PlayType.SOCIAL
              AND (
                t.sourceTournamentId IS NOT NULL
                OR (SELECT COUNT(tu2) FROM TournamentUser tu2
                    WHERE tu2.tournamentId = t.id AND tu2.deletedAt IS NULL) >= 2
              )
            )
            OR (
              :playType = com.depromeet.piki.tournament.domain.PlayType.SOLO
              AND t.sourceTournamentId IS NULL
              AND (SELECT COUNT(tu2) FROM TournamentUser tu2
                   WHERE tu2.tournamentId = t.id AND tu2.deletedAt IS NULL) < 2
            )
          )
        ORDER BY t.createdAt DESC, t.id DESC
        """,
    )
    fun findVisibleByUserId(
        @Param("userId") userId: UUID,
        @Param("statuses") statuses: Collection<TournamentStatus>,
        @Param("playType") playType: PlayType?,
        pageable: Pageable,
    ): List<Tournament>

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
