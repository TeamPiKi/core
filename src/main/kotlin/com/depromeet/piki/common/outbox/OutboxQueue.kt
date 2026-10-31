package com.depromeet.piki.common.outbox

import org.springframework.data.domain.Limit
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

// 집기·회수는 final 이라 하위 클래스가 RC 를 빼지 못함. 대신 하위 클래스에 프록시를 거는 어노테이션을 붙이면 필드 없는 프록시에서 돌아 NPE
abstract class OutboxQueue<T : Outbox>(
    private val jpaRepository: OutboxJpaRepository<T>,
    transactionManager: PlatformTransactionManager,
    val policy: OutboxPolicy,
) {
    // RR 이면 잠금 읽기가 status 인덱스의 gap 까지 잠가 새 PENDING insert 가 막힘(실측 #1261)
    private val readCommitted =
        TransactionTemplate(transactionManager).apply {
            isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
        }

    // 회수 트랜잭션 안에서 돈다
    protected abstract fun onExhausted(outbox: T)

    fun claim(limit: Int): List<OutboxAttempt> =
        inReadCommitted {
            jpaRepository.findPendingForUpdate(Limit.of(limit)).map { outbox ->
                outbox.claim()
                outbox.attempt()
            }
        }

    fun requeue(attempt: OutboxAttempt) =
        inReadCommitted {
            jpaRepository.findHeldForUpdate(attempt)?.requeue(attempt)
            Unit
        }

    fun recoverStuck(limit: Int): List<OutboxRecovery> =
        inReadCommitted {
            jpaRepository.findStuckForUpdate(LocalDateTime.now().minus(policy.stuckAfter), Limit.of(limit)).map { outbox ->
                val outcome = outbox.recover(policy.maxRecoveries)
                when (outcome) {
                    RecoveryOutcome.REQUEUED -> Unit
                    RecoveryOutcome.EXHAUSTED -> onExhausted(outbox)
                }
                OutboxRecovery(outbox.getId(), outcome)
            }
        }

    // 바깥 트랜잭션에 합류하면 Spring 이 isolation 설정을 무시함
    private fun <R : Any> inReadCommitted(block: () -> R): R {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "아웃박스 집기·되돌리기·회수는 바깥 트랜잭션 안에서 부를 수 없다" }
        return readCommitted.execute { block() }
    }
}
