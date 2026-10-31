package com.depromeet.piki.common.outbox

// 집기·회수 조회는 RC 를 거치도록 OutboxQueue 만 쓰게 하고 여기 열지 않음
interface OutboxRepository<T : Outbox> {
    fun save(outbox: T): T

    fun findHeldForUpdate(attempt: OutboxAttempt): T?
}
