package com.depromeet.piki.notification.fcm.service

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

// FCM 토큰 등록 동시성 검증(#396) — 같은 토큰으로 동시 등록 시 check-then-act 가 UNIQUE 충돌(500)을 내던 것을
// retry-upsert 로 흡수하는지 본다. @Transactional 자동 롤백을 쓰지 않는다 — 별도 트랜잭션 동시 진행(각 register
// 시도가 독립 커밋돼야 충돌이 재현)이 시뮬레이션의 본질이라, 격리된 token/deviceId 를 쓰고 끝에서 만든 행을
// 직접 정리한다. (CLAUDE.md "동시성·시간 의존 통합 테스트" 분류)
class UserDeviceRegisterConcurrencyIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var userDeviceService: UserDeviceService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private fun countByToken(token: String): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_devices WHERE fcm_token = ?", Int::class.java, token) ?: 0

    private fun deviceIdByToken(token: String): String? =
        jdbcTemplate.queryForObject("SELECT device_id FROM user_devices WHERE fcm_token = ?", String::class.java, token)

    @Test
    fun `같은 유저 기기 토큰으로 동시 등록해도 500 없이 기기 1개로 수렴한다`() {
        val token = "concurrent-${UUID.randomUUID()}"
        val userId = UUID.randomUUID()
        val deviceId = "device-${UUID.randomUUID()}"
        val threadCount = 4
        val pool = Executors.newFixedThreadPool(threadCount)
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())

        try {
            repeat(threadCount) {
                pool.submit {
                    ready.countDown()
                    start.await()
                    runCatching { userDeviceService.register(userId, deviceId, token) }
                        .onFailure { errors.add(it) }
                }
            }
            // 모든 스레드가 시작 게이트에 도달했는지 강제 검증 — 안 하면 느린 CI 에서 일부가 준비 전에 start 가
            // 풀려 동시성이 약해지고 race 가 거짓양성으로 통과할 수 있다.
            assertTrue(ready.await(5, TimeUnit.SECONDS), "모든 스레드가 시작 게이트에 준비돼야 한다")
            start.countDown() // 동시 출발
            pool.shutdown()
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "동시 작업이 시간 내 끝나야 한다")

            assertEquals(emptyList(), errors.map { it.message }, "동시 등록에서 예외(500)가 나면 안 된다")
            assertEquals(1, countByToken(token), "동시 등록이 기기 1 row 로 수렴해야 한다")
        } finally {
            // 실패 경로 정리 — ready.await 단언이 실패하면 start.countDown()/shutdown() 에 못 닿아 작업 스레드가
            // start.await() 에서 영구 대기하고, non-daemon 풀이라 테스트 프로세스가 hang 된다. finally 에서 게이트
            // 해제 + 풀 강제 종료를 보장한다 (정상 경로에선 이미 0/shutdown 이라 멱등하게 no-op).
            start.countDown()
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
            jdbcTemplate.update("DELETE FROM user_devices WHERE fcm_token = ?", token)
        }
    }

    @Test
    fun `다른 기기 row 가 들고 있던 토큰을 동시 재등록해도 500 없이 토큰 1개로 수렴한다`() {
        // delete 경쟁 재현 — 토큰을 들고 있던 기존 row(holder)가 있고, 같은 토큰을 다른 deviceId 로
        // (앱 재설치로 device_id 가 바뀐 같은 유저의 중복 요청 등) 동시 재등록하면, 모든 시도가 holder 를
        // release 하려다 엔티티 delete 의 "정확히 1 row" 단언이 깨져 StaleStateException(→500)이 났다.
        // bulk delete 로 멱등 해제하도록 고친 뒤엔 500 없이 토큰 1 row 로 수렴해야 한다(#396 후속).
        val token = "reassign-${UUID.randomUUID()}"
        val userId = UUID.randomUUID()
        val oldDeviceId = "old-device-${UUID.randomUUID()}"
        val newDeviceId = "new-device-${UUID.randomUUID()}"
        userDeviceService.register(userId, oldDeviceId, token) // 기존 holder 생성

        val threadCount = 4
        val pool = Executors.newFixedThreadPool(threadCount)
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())

        try {
            repeat(threadCount) {
                pool.submit {
                    ready.countDown()
                    start.await()
                    runCatching { userDeviceService.register(userId, newDeviceId, token) }
                        .onFailure { errors.add(it) }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "모든 스레드가 시작 게이트에 준비돼야 한다")
            start.countDown() // 동시 출발
            pool.shutdown()
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "동시 작업이 시간 내 끝나야 한다")

            assertEquals(emptyList(), errors.map { it.message }, "delete 경쟁에서 예외(StaleStateException → 500)가 나면 안 된다")
            assertEquals(1, countByToken(token), "재배정이 토큰 1 row 로 수렴해야 한다")
            assertEquals(newDeviceId, deviceIdByToken(token), "토큰은 새 deviceId row 로 재배정돼야 한다 (수렴한 1 row 가 옛 holder 가 아님)")
        } finally {
            start.countDown()
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
            jdbcTemplate.update("DELETE FROM user_devices WHERE fcm_token = ?", token)
        }
    }
}

// 참고 — "서로 다른 유저가 같은 토큰을 동시 등록"하는 race 는 일부러 검증하지 않는다. FCM 토큰은 앱 설치(기기)
// 하나당 전역 유일이라, 서로 다른 유저가 동일 토큰을 같은 순간에 등록하는 상황 자체가 현실에 없다(#396 이 노리는
// race 는 같은 클라이언트의 중복/재등록 = 위 두 테스트). 그런 N-way 재배정도 PK 기준 holder bulk delete 로
// StaleStateException 없이 1 row 로 수렴하지만, 비현실 시나리오라 별도 테스트로 고정하지는 않는다.
