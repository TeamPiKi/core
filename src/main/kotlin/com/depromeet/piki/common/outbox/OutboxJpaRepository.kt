package com.depromeet.piki.common.outbox

import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import org.springframework.data.repository.NoRepositoryBean
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

// Hibernate LockOptions.SKIP_LOCKED. 동시에 집는 디스패처가 서로를 기다리지 않게 한다(#770 교착).
private const val SKIP_LOCKED = "-2"

@NoRepositoryBean
interface OutboxJpaRepository<T : Outbox> : JpaRepository<T, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = SKIP_LOCKED))
    @Query(
        "select o from #{#entityName} o where o.status = com.depromeet.piki.common.outbox.OutboxStatus.PENDING " +
            "and o.deletedAt is null order by o.createdAt asc, o.id asc",
    )
    fun findPendingForUpdate(limit: Limit): List<T>

    // 집을 때 updated_at 이 갱신되므로 집은 시각으로 읽음. 처리 중에 행을 고치면 그만큼 회수가 늦어짐
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = SKIP_LOCKED))
    @Query(
        "select o from #{#entityName} o where o.status = com.depromeet.piki.common.outbox.OutboxStatus.PROCESSING " +
            "and o.updatedAt < :claimedBefore and o.deletedAt is null order by o.updatedAt asc, o.id asc",
    )
    fun findStuckForUpdate(
        @Param("claimedBefore") claimedBefore: LocalDateTime,
        limit: Limit,
    ): List<T>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from #{#entityName} o where o.id = :id and o.deletedAt is null")
    fun findByIdForUpdate(
        @Param("id") id: Long,
    ): T?
}

fun <T : Outbox> OutboxJpaRepository<T>.findHeldForUpdate(attempt: OutboxAttempt): T? =
    findByIdForUpdate(attempt.outboxId)?.takeIf { it.isHeldBy(attempt) }
