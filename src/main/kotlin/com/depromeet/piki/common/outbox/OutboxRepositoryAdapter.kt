package com.depromeet.piki.common.outbox

// 구현체의 @Repository 예외 변환 프록시(CGLIB)가 가로채야 하므로 final 로 막지 않음
abstract class OutboxRepositoryAdapter<T : Outbox>(
    private val jpaRepository: OutboxJpaRepository<T>,
) : OutboxRepository<T> {
    override fun save(outbox: T): T = jpaRepository.save(outbox)

    override fun findHeldForUpdate(attempt: OutboxAttempt): T? = jpaRepository.findHeldForUpdate(attempt)
}
