package com.depromeet.piki.common.exception

import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLIntegrityConstraintViolationException

private const val MYSQL_DUPLICATE_ENTRY = 1062

// 벤더 코드만 보면 어느 unique 인지 모른다. 같은 INSERT 에 제약이 여럿 걸리면 무관한 충돌까지 삼키므로 제약명까지 맞춘다.
fun DataIntegrityViolationException.isDuplicateKey(constraint: String): Boolean {
    val cause = mostSpecificCause as? SQLIntegrityConstraintViolationException ?: return false
    return cause.errorCode == MYSQL_DUPLICATE_ENTRY && cause.message?.contains(constraint) == true
}

// 트랜잭션 밖에서만 부른다. 안에서 잡으면 rollback-only 가 찍혀 커밋 때 터진다.
inline fun <T> skippingDuplicateKey(
    constraint: String,
    block: () -> T,
): T? =
    try {
        block()
    } catch (e: DataIntegrityViolationException) {
        if (e.isDuplicateKey(constraint)) null else throw e
    }
