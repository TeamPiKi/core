package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ParseRequest
import com.depromeet.piki.item.domain.ParseRequestStatus
import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

// FOR UPDATE 에 SKIP LOCKED 를 더하는 힌트 값 (Hibernate LockOptions.SKIP_LOCKED = -2).
// 잠긴 행을 기다리지 않고 건너뛴다. 스캔이 절대 기다리지 않으면 스캔이 끼는 교착 사이클이 성립하지 않는다
// (버전 테이블에서 실측한 InnoDB 교착의 재발 방지 — ItemSnapshotJpaRepository 의 같은 상수 주석이 경위를 갖는다).
private const val SKIP_LOCKED = "-2"

interface ParseRequestJpaRepository : JpaRepository<ParseRequest, Long> {
    fun findByResultSnapshotIdAndDeletedAtIsNull(resultSnapshotId: Long): ParseRequest?

    // 전이의 fence 검사와 쓰기를 한 락 구간으로 묶기 위한 비관적 락 단건 조회.
    // 결과 전이는 이 요청 락을 먼저 잡고 버전 락을 뒤에 잡는다 — 두 행을 쓰는 경로가 전부 같은 순서라 교착이 없다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ParseRequest r where r.id = :id and r.deletedAt is null")
    fun findByIdForUpdate(
        @Param("id") id: Long,
    ): ParseRequest?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = SKIP_LOCKED))
    @Query(
        "select r from ParseRequest r where r.status = :status and r.deletedAt is null " +
            "order by r.createdAt asc, r.id asc",
    )
    fun findByStatusForUpdate(
        @Param("status") status: ParseRequestStatus,
        pageable: Pageable,
    ): List<ParseRequest>

    // stale 스캔은 heartbeatAt 을 본다. 집기가 이 값을 시작시키고 박동이 밀므로, threshold 이전이면
    // "박동이 연속으로 끊겼는데 반납도 없었다 = 프로세스가 죽었다" 는 뜻이다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = SKIP_LOCKED))
    @Query(
        "select r from ParseRequest r where r.status = :status and r.heartbeatAt < :threshold " +
            "and r.deletedAt is null order by r.heartbeatAt asc, r.id asc",
    )
    fun findStaleByStatusForUpdate(
        @Param("status") status: ParseRequestStatus,
        @Param("threshold") threshold: LocalDateTime,
        pageable: Pageable,
    ): List<ParseRequest>

    // 마감은 createdAt 을 본다. 움직이지 않는 시계라 박동이 멀쩡한 느린 실행도, 슬롯이 없어 집히지 못한 PENDING 도 걸린다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = SKIP_LOCKED))
    @Query(
        "select r from ParseRequest r where r.status in :statuses and r.createdAt < :threshold " +
            "and r.deletedAt is null order by r.createdAt asc, r.id asc",
    )
    fun findOverdueForUpdate(
        @Param("statuses") statuses: Collection<ParseRequestStatus>,
        @Param("threshold") threshold: LocalDateTime,
        pageable: Pageable,
    ): List<ParseRequest>

    // 소유권 획득 — 조건부 test-and-set 이라 같은 요청에 두 워커가 제출돼도 하나만 1행을 받는다.
    // bulk update 라 auditing 을 우회하므로 updatedAt 을 명시로 넘긴다.
    @Modifying
    @Query(
        "update ParseRequest r set r.attemptCount = r.attemptCount + 1, r.heartbeatAt = :now, r.updatedAt = :now " +
            "where r.id = :id and r.status = :status and r.attemptCount = :expectedAttempt and r.deletedAt is null",
    )
    fun acquireOwnership(
        @Param("id") id: Long,
        @Param("status") status: ParseRequestStatus,
        @Param("expectedAttempt") expectedAttempt: Int,
        @Param("now") now: LocalDateTime,
    ): Int

    // 박동 — attemptCount 는 건드리지 않아 소유권이 옮겨가지 않는다. 0행이면 소유권 상실(재획득됐거나 이미 종결).
    @Modifying
    @Query(
        "update ParseRequest r set r.heartbeatAt = :now, r.updatedAt = :now " +
            "where r.id = :id and r.status = :status and r.attemptCount = :attempt and r.deletedAt is null",
    )
    fun renewOwnership(
        @Param("id") id: Long,
        @Param("status") status: ParseRequestStatus,
        @Param("attempt") attempt: Int,
        @Param("now") now: LocalDateTime,
    ): Int

    // 병합(#825) — 진 item 의 요청을 이긴 item 으로. item_snapshots 재부모화와 같은 트랜잭션에서 따라간다.
    @Modifying
    @Query(
        "update ParseRequest r set r.itemId = :toItemId, r.updatedAt = :now " +
            "where r.itemId = :fromItemId and r.deletedAt is null",
    )
    fun reparentAll(
        @Param("fromItemId") fromItemId: Long,
        @Param("toItemId") toItemId: Long,
        @Param("now") now: LocalDateTime,
    ): Int
}
