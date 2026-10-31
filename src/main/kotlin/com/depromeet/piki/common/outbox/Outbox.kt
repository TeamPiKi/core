package com.depromeet.piki.common.outbox

import com.depromeet.piki.common.domain.LongBaseEntity
import jakarta.persistence.Column
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.MappedSuperclass

@MappedSuperclass
abstract class Outbox : LongBaseEntity() {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: OutboxStatus = OutboxStatus.PENDING
        protected set

    @Column(nullable = false)
    var recoveryCount: Int = 0
        protected set

    fun attempt(): OutboxAttempt = OutboxAttempt(getId(), recoveryCount)

    fun isHeldBy(attempt: OutboxAttempt): Boolean = status == OutboxStatus.PROCESSING && recoveryCount == attempt.recoveryCount

    fun isInProgress(): Boolean = status == OutboxStatus.PENDING || status == OutboxStatus.PROCESSING

    fun isFailed(): Boolean = status == OutboxStatus.FAILED

    fun claim() = transit(from = OutboxStatus.PENDING, to = OutboxStatus.PROCESSING)

    fun requeue(attempt: OutboxAttempt) = settle(attempt, OutboxStatus.PENDING)

    fun succeed(attempt: OutboxAttempt) = settle(attempt, OutboxStatus.SUCCEEDED)

    fun fail(attempt: OutboxAttempt) = settle(attempt, OutboxStatus.FAILED)

    fun recover(maxRecoveries: Int): RecoveryOutcome {
        if (recoveryCount >= maxRecoveries) {
            transit(from = OutboxStatus.PROCESSING, to = OutboxStatus.FAILED)
            return RecoveryOutcome.EXHAUSTED
        }
        transit(from = OutboxStatus.PROCESSING, to = OutboxStatus.PENDING)
        recoveryCount++
        return RecoveryOutcome.REQUEUED
    }

    // 회수 뒤 다시 집힌 행도 status 는 PROCESSING 이라 시도까지 맞춰 봐야 늦은 워커의 덮어쓰기를 막음
    private fun settle(
        attempt: OutboxAttempt,
        to: OutboxStatus,
    ) {
        check(isHeldBy(attempt)) { "${javaClass.simpleName} ${getIdOrNull()} 은 시도 $attempt 가 쥐고 있지 않아 $to 로 갈 수 없다" }
        status = to
    }

    private fun transit(
        from: OutboxStatus,
        to: OutboxStatus,
    ) {
        check(status == from) { "${javaClass.simpleName} ${getIdOrNull()} 은 $status 라 $to 로 갈 수 없다" }
        status = to
    }
}
