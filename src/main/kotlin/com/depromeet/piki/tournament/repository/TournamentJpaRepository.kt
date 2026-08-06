package com.depromeet.piki.tournament.repository

import com.depromeet.piki.tournament.domain.Tournament
import com.depromeet.piki.tournament.domain.TournamentPlayType
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

    // 목록 화면 쿼리 — 내 tournament_user 멤버십과 조인해 가시성 필터까지 DB 에서 끝낸다.
    // 필터·정렬·limit 이 앱으로 올라오면 홈 카드(limit=3) 한 번에 내 전체 이력의 참가자·프로필을 선로드하게 된다.
    // uk_tournament_users (tournament_id, user_id) 가 (유저, 토너먼트) 당 멤버십 행을 1개로 보장해 조인이 행을 늘리지 않는다.
    // createdAt 동률 시 어느 행이 LIMIT 에 잘릴지 비결정적이므로 생성 순서와 일치하는 id 로 tie-break 한다.
    // t._ownerTournamentUserId — 엔티티가 backing field 캡슐화(private var _ownerTournamentUserId)라 JPA 속성명이 field 이름이다.
    //
    // 가시성은 "나에게 이 토너먼트가 어떤 상태냐"(per-user effective status)로 판정한다(#882).
    //  (owner) 내가 owner 인 것 — 내가 만든 ROOT 와 내가 플레이해 소유한 CLONE. 전역 status 그대로 필터한다.
    //      소유 판정은 CLONE 여부가 아니라 _ownerTournamentUserId 로만 한다. 초대코드 join 은 ROOT 를 강제하지
    //      않아 남의 CLONE 에 참여자로 들어갈 수 있고(POST /tournaments/{id}/join), "CLONE 이면 내 것" 으로 보면
    //      그 방이 ownedOnly=true(홈, 내가 생성한 것) 결과에 섞인다. 남의 CLONE 은 (참여) 갈래도 ROOT 한정이라
    //      목록에서 빠진다 — 남의 개인 브래킷이라 내 카드로 보일 자리가 없다.
    //  (참여) 내가 참여자지만 owner 가 아니고 아직 내 CLONE 이 없는 ROOT: 그 방은 나에겐 '완주 안 함' 이라
    //      완료로 치지 않는다. 완료된 ROOT 도 나에겐 IN_PROGRESS(진행중)로 캡해 노출한다 — 방장이 완료해도
    //      진행중 탭에서 사라지지 않고, 완료 탭엔 안 뜬다. 내가 이미 내 CLONE 을 만들었으면(NOT EXISTS 실패)
    //      이 ROOT 는 숨고 그 CLONE 이 (owner)로 표시된다(카드 중복 방지).
    // ownedOnly — 홈(내가 생성한 것만)은 TRUE 로 (참여) 갈래를 끈다. 탭은 미지정(FALSE)이라 참여까지 본다.
    //  status 를 대체하지 않고 AND 로 함께 걸린다 — 홈이 상태 무관인 것은 status 를 안 보내기 때문이다.
    // includeInProgress — 요청 statuses 에 IN_PROGRESS 가 포함되는지(서비스가 계산). (참여)의 완료 ROOT 를
    //  IN_PROGRESS 로 캡해 노출할지 판단하는 플래그. nullable enum 을 쿼리에 넣지 않는 boolean 패턴(#837 과 동일).
    //
    // playType(솔로/소셜)은 저장된 컬럼이 아니라 참가 결과로 파생되는 상태다(TournamentPlayType 참고).
    // 파생값이라 앱에서 거르면 limit 이 파생 필터보다 먼저 걸려 "SOCIAL 3개" 를 요구했는데 그보다 적게 나오므로,
    // status·정렬·limit 과 같은 자리에서 DB 가 함께 판정해야 한다.
    // SOLO 와 SOCIAL 은 서로 여집합이지만(참가자 수는 조인 때문에 항상 1 이상) 각 갈래를 그대로 적어 의도를 남긴다.
    // 미지정이면 includeSolo·includeSocial 이 둘 다 TRUE 라 이 술어가 항상 성립한다(= 필터 없음) —
    // statuses 를 "전체 IN" 으로 바인딩해 쿼리를 2벌로 나누지 않는 것과 같은 방식.
    @Query(
        """
        SELECT t FROM Tournament t
        JOIN TournamentUser tu ON tu.tournamentId = t.id
        WHERE tu.userId = :userId
          AND tu.deletedAt IS NULL
          AND t.deletedAt IS NULL
          AND (
            (t._ownerTournamentUserId = tu.id AND t.status IN :statuses)
            OR (
              :ownedOnly = FALSE
              AND t.sourceTournamentId IS NULL
              AND t._ownerTournamentUserId <> tu.id
              AND NOT EXISTS (
                SELECT 1 FROM Tournament c
                JOIN TournamentUser ctu ON ctu.id = c._ownerTournamentUserId
                WHERE c.sourceTournamentId = t.id
                  AND ctu.userId = :userId
                  AND c.deletedAt IS NULL
              )
              AND (
                (t.status <> com.depromeet.piki.tournament.domain.TournamentStatus.COMPLETED AND t.status IN :statuses)
                OR (t.status = com.depromeet.piki.tournament.domain.TournamentStatus.COMPLETED AND :includeInProgress = TRUE)
              )
            )
          )
          AND (
            (
              :includeSocial = TRUE
              AND (
                t.sourceTournamentId IS NOT NULL
                OR (
                  SELECT COUNT(tu2.id) FROM TournamentUser tu2
                  WHERE tu2.tournamentId = t.id AND tu2.deletedAt IS NULL
                ) > 1
              )
            )
            OR (
              :includeSolo = TRUE
              AND t.sourceTournamentId IS NULL
              AND (
                SELECT COUNT(tu2.id) FROM TournamentUser tu2
                WHERE tu2.tournamentId = t.id AND tu2.deletedAt IS NULL
              ) = 1
            )
          )
        ORDER BY t.createdAt DESC, t.id DESC
        """,
    )
    fun findVisibleByUserId(
        @Param("userId") userId: UUID,
        @Param("statuses") statuses: Collection<TournamentStatus>,
        @Param("ownedOnly") ownedOnly: Boolean,
        @Param("includeInProgress") includeInProgress: Boolean,
        @Param("includeSolo") includeSolo: Boolean,
        @Param("includeSocial") includeSocial: Boolean,
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
