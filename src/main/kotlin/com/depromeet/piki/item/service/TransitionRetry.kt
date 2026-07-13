package com.depromeet.piki.item.service

import org.slf4j.LoggerFactory
import org.springframework.dao.RecoverableDataAccessException
import org.springframework.dao.TransientDataAccessException
import org.springframework.stereotype.Component
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException
import kotlin.random.Random

/**
 * 파싱 결과의 상태 전이(markReady/markFailed) write 를 일시 DB 오류에 한해 인라인 재시도한다.
 *
 * 추출(원격 extractor 호출)은 이미 성공한 뒤라, 전이 write 가 일시 DB 오류(데드락·lock timeout 등)로 실패했다고 추출까지 버리면
 * 낭비다. PROCESSING 으로 두고 recover 가 잡으면 추출 전체를 재실행하므로(외부 의존성 재호출), 그 대신 추출 재실행 없이
 * 전이 write 만 짧게 N회 다시 시도한다. 상한까지 실패하면 예외를 그대로 전파해 호출부가 FAILED 로 종결한다.
 *
 * 재시도 대상은 "재시도하면 될 수 있는" 것뿐 — 영구 오류(도메인 검증 위반·sweeper 레이스로 이미 전이됨 등)는 즉시 전파한다.
 * extractor 서비스의 GeminiRetry(재시도 분류를 한 곳에 모음)와 같은 결이되, DB 예외는 category 가 없어
 * Spring DataAccessException 타입으로 가른다. dirty-checking UPDATE 는 일시 장애가 commit(flush) 시점에 터져
 * `TransactionSystemException` 으로 감싸지기도 하므로, cause 사슬에서 다음 타입을 찾는다:
 * Spring 의 [TransientDataAccessException](deadlock·lock timeout 등) · [RecoverableDataAccessException](복구 후 재시도 가능),
 * JDBC 의 [SQLTransientException](deadlock 40001·lock timeout 1205 의 `SQLTransactionRollbackException` 포함) · [SQLRecoverableException].
 *
 * 한계: 실제 드라이버·Hibernate 버전에 따라 commit 시점 lock 실패가 위 타입이 아닌 일반 `SQLException` 으로 올 수 있고, 그 경우
 * 재시도되지 않는다. SQLState(40001·1205) 기반 판정과 실DB 검증은 후속(#643 의 deeper 과제)으로 남긴다.
 *
 * block 은 `@Transactional` 전이 메서드를 프록시로 호출해 매 시도가 새 트랜잭션이 되게 해야 한다(self-invocation 금지).
 */
@Component
class TransitionRetry {
    private val log = LoggerFactory.getLogger(javaClass)

    fun <T> execute(block: () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                if (!isTransient(e) || attempt >= MAX_ATTEMPTS) throw e
                log.warn("상태 전이 일시 실패 {}/{} — 재시도: {}", attempt, MAX_ATTEMPTS - 1, e.message)
                // jitter 로 lockstep 재충돌(서로 데드락난 두 tx 가 같은 시점에 재시도해 또 충돌)을 깬다(GeminiRetry 의 jitter 와 같은 목적).
                // 백오프 중 인터럽트(셧다운 등)면 interrupt 플래그를 복원하고 원래 일시 예외를 전파한다 —
                // InterruptedException 으로 실제 원인을 가리지 않고, 협조적 셧다운도 깨지 않는다.
                try {
                    Thread.sleep(BACKOFF_MS + Random.nextLong(JITTER_MS))
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
                attempt++
            }
        }
    }

    // cause 사슬에 재시도 가능한 DB 예외가 있으면 일시로 본다(직접 던져졌든 TransactionSystemException 등에 감싸졌든).
    // generateSequence 가 cause 가 없을 때 사슬을 끝내고, take 로 병리적 순환 cause 에도 멈춘다.
    private fun isTransient(e: Throwable): Boolean =
        generateSequence(e) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .any {
                it is TransientDataAccessException || it is RecoverableDataAccessException ||
                    it is SQLTransientException || it is SQLRecoverableException
            }

    companion object {
        // 짧은 인라인 재시도 — 단건 ≤60s·절대 3분 초과 금지 제약 안 (3회 × 최대 100ms = 최악 약 200ms 지연).
        private const val MAX_ATTEMPTS = 3
        private const val BACKOFF_MS = 50L
        private const val JITTER_MS = 50L
        private const val MAX_CAUSE_DEPTH = 10
    }
}
