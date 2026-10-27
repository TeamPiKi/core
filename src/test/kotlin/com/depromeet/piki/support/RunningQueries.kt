package com.depromeet.piki.support

import org.springframework.jdbc.core.JdbcTemplate

// 테스트 계정은 PROCESS 권한이 없어 잠금 대기 표를 못 읽는다. 같은 계정의 연결은 보여 잠금을 기다리는 실행 중 쿼리로 대기를 본다
fun JdbcTemplate.runningQueries(pattern: String): Int =
    queryForObject(
        "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID <> CONNECTION_ID() AND INFO LIKE ?",
        Int::class.java,
        pattern,
    ) ?: 0
