package com.depromeet.piki.item.service

import org.hibernate.exception.ConstraintViolationException
import org.hibernate.exception.ConstraintViolationException.ConstraintKind
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException

// 새 트랜잭션의 스냅샷만 먼저 커밋된 확정을 본다. 그래서 attempt 는 트랜잭션 밖에서 불러 매번 새 트랜잭션을 열어야 한다
object UniqueConflictRetry {
    private val log = LoggerFactory.getLogger(javaClass)

    fun <T> once(
        subject: String,
        attempt: () -> T,
    ): T =
        try {
            attempt()
        } catch (e: DataIntegrityViolationException) {
            val violation = (e.cause as? ConstraintViolationException)?.takeIf { it.kind == ConstraintKind.UNIQUE } ?: throw e
            log.info("item.confirm.retry {} key={} 먼저 확정된 행을 보고 다시 실행", subject, violation.constraintName)
            attempt()
        }
}
