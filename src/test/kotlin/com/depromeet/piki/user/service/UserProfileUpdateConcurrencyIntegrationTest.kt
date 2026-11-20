package com.depromeet.piki.user.service

import com.depromeet.piki.support.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 여러 user 가 동시에 같은 닉네임으로 프로필을 수정해도 전원 성공한다 — 중복 허용(#1287)의 동시 버전.
// @Transactional 자동 롤백을 쓰지 않는다: 별도 트랜잭션이 각자 커밋돼야 "DB 에 같은 닉네임 여러 행" 이
// 실제로 남는 것을 볼 수 있다. 격리된 마커 닉네임을 쓰고 끝에서 만든 행을 직접 정리한다.
// (CLAUDE.md "동시성·시간 의존 통합 테스트" 분류)
//
// 이 테스트가 마이그레이션의 negative control 이다: uq_users_nickname 이 되살아나면 네 스레드 중
// 하나만 성공하고 나머지가 제약 위반으로 떨어져 아래 단언이 깨진다. 이전 버전은 정확히 그 반대
// (하나만 성공하고 나머지는 409)를 고정하고 있었다.
class UserProfileUpdateConcurrencyIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var userService: UserService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `여러 user 가 동시에 같은 닉네임으로 프로필 수정하면 전원 성공한다`() {
        val mk = UUID.randomUUID().toString().take(4)
        val target = "q${mk}t"
        val threadCount = 4
        // 서로 다른 시작 닉네임으로 user 4명 생성(각각 커밋). 모두 target 닉네임으로 동시 변경 시도한다.
        val userIds = (0 until threadCount).map { i -> userService.createGuestWithNickname("q$mk$i").id }

        val pool = Executors.newFixedThreadPool(threadCount)
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val successes = Collections.synchronizedList(mutableListOf<UUID>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())

        try {
            userIds.forEach { userId ->
                pool.submit {
                    ready.countDown()
                    start.await()
                    runCatching { userService.updateProfile(userId, target, null) }
                        .onSuccess { successes.add(userId) }
                        .onFailure { e -> failures.add(e) }
                }
            }
            // 모든 스레드가 시작 게이트에 도달했는지 강제 검증 — 안 하면 느린 CI 에서 일부가 준비 전에
            // start 가 풀려 동시성이 약해지고 거짓양성으로 통과할 수 있다.
            assertTrue(ready.await(5, TimeUnit.SECONDS), "모든 스레드가 시작 게이트에 준비돼야 한다")
            start.countDown() // 동시 출발
            pool.shutdown()
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "동시 작업이 시간 내 끝나야 한다")

            assertEquals(
                emptyList(),
                failures.map { it::class.simpleName },
                "중복 허용이므로 닉네임 때문에 실패하는 스레드가 없어야 한다",
            )
            assertEquals(threadCount, successes.size, "네 스레드 모두 target 닉네임을 얻어야 한다")

            val withTarget = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE nickname = ?", Int::class.java, target) ?: 0
            assertEquals(threadCount, withTarget, "DB 에 target 닉네임 user 가 네 명 남아야 한다")
        } finally {
            // 실패 경로에서도 executor 를 반드시 종료한다 — ready.await 단언이 start.countDown 전에 실패하면
            // worker 가 start.await() 에 non-daemon 스레드로 영원히 묶여 테스트 JVM 이 종료 못 한다. gate 를
            // 풀어 대기 중인 worker 를 깨우고 shutdownNow + awaitTermination 으로 종료를 확인한 뒤 DB 를 정리한다.
            // (happy path 는 이미 shutdown+terminated 라 no-op)
            start.countDown()
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
            // @Transactional 자동 롤백이 없으므로 만든 행을 직접 정리 (마커 닉네임 prefix 로 삭제 → UUID 바이너리 바인딩 회피)
            jdbcTemplate.update("DELETE FROM users WHERE nickname LIKE ?", "q$mk%")
        }
    }
}
