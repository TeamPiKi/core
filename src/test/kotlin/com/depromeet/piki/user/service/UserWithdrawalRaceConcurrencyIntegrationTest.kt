package com.depromeet.piki.user.service

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.notification.fcm.repository.UserDeviceRepository
import com.depromeet.piki.notification.fcm.service.UserDeviceService
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.domain.UserException
import com.depromeet.piki.wishlist.service.WishPersistenceService
import com.depromeet.piki.wishlist.service.dto.WishWithItem
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// 활성 유저 확인과 쓰기 사이의 check-then-use 경합 차단(#776) 검증.
// 탈퇴(WithdrawalService.withdraw) cascade 와 유저 쓰기 경로(프로필 수정·wish 등록·FCM 등록)가
// user 행 비관락(findActiveByIdForUpdate)으로 직렬화돼, 어느 인터리빙이든 종단 상태가 tombstone 이고
// 죽은 유저를 가리키는 자식 행이 남지 않음(계정 부활·PII 복원·orphan 자식 금지)을 확인한다.
//
// @Transactional 자동 롤백을 쓰지 않는다 — 탈퇴와 쓰기가 별도 트랜잭션으로 각자 커밋돼야 race 가 재현되기 때문.
// 만든 행은 매 반복 끝에서 userId 로 직접 정리한다. (CLAUDE.md "동시성·시간 의존 통합 테스트" 분류)
//
// 이 동시성 테스트들은 fix 의 negative control 이다: 잠금(FOR UPDATE)을 걷어내 findActiveById(비잠금)로
// 되돌리면, updateProfile 이 stale 스냅샷(deletedAt=null)을 full-column UPDATE 로 되써 계정이 부활하거나
// (deleted_at NULL + 원본 닉네임), 탈퇴 후 wish/기기 행이 orphan 으로 남아 아래 단언들이 깨진다.
class UserWithdrawalRaceConcurrencyIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var userService: UserService

    @Autowired private lateinit var withdrawalService: WithdrawalService

    @Autowired private lateinit var wishPersistenceService: WishPersistenceService

    @Autowired private lateinit var userDeviceService: UserDeviceService

    @Autowired private lateinit var userDeviceRepository: UserDeviceRepository

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    private fun newMember(): UUID = userService.createMember("wr${UUID.randomUUID().toString().take(4)}").id

    private fun cleanupUser(userId: UUID) {
        val idBytes = uuidToBytes(userId)
        jdbcTemplate.update("DELETE FROM user_devices WHERE user_id = ?", idBytes)
        jdbcTemplate.update("DELETE FROM wishes WHERE user_id = ?", idBytes)
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", idBytes)
    }

    private data class UserRow(val deletedAt: Any?, val nickname: String)

    private fun userRow(userId: UUID): UserRow =
        jdbcTemplate.queryForMap("SELECT deleted_at, nickname FROM users WHERE id = ?", uuidToBytes(userId))
            .let { UserRow(it["deleted_at"], it["nickname"] as String) }

    private fun wishCount(userId: UUID): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wishes WHERE user_id = ?", Int::class.java, uuidToBytes(userId)) ?: 0

    // 탈퇴와 writeAction 을 동시 출발시키고 둘 다 끝날 때까지 기다린다. 예외는 흡수(어느 쪽이 이기든 유효한 결과).
    private fun raceWithWithdrawal(
        pool: java.util.concurrent.ExecutorService,
        userId: UUID,
        writeAction: () -> Unit,
    ) {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        listOf<() -> Unit>(writeAction, { withdrawalService.withdraw(userId) }).forEach { action ->
            pool.submit {
                ready.countDown()
                start.await()
                try {
                    runCatching { action() }
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS), "두 스레드가 시작 게이트에 준비돼야 한다")
        start.countDown()
        assertTrue(done.await(15, TimeUnit.SECONDS), "동시 작업이 시간 내 끝나야 한다")
    }

    @Test
    fun `탈퇴와 프로필 수정이 동시에 일어나도 종단은 tombstone 이고 계정이 부활하지 않는다`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(ITERATIONS) { i ->
                val userId = newMember()
                try {
                    raceWithWithdrawal(pool, userId) {
                        userService.updateProfile(userId, "wn${UUID.randomUUID().toString().take(4)}", null)
                    }
                    val row = userRow(userId)
                    assertNotNull(row.deletedAt, "탈퇴가 관여했으면 종단은 반드시 tombstone(deleted_at 채워짐) — 계정 부활 금지 (iter=$i)")
                    assertTrue(
                        row.nickname.startsWith(User.WITHDRAWN_NICKNAME_PREFIX),
                        "tombstone 은 익명 닉네임이어야 한다 — 원본 PII 복원 금지 (iter=$i, nickname=${row.nickname})",
                    )
                } finally {
                    cleanupUser(userId)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `탈퇴와 wish 등록이 동시에 일어나도 종단적으로 tombstone 유저의 wish 가 남지 않는다`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(ITERATIONS) { i ->
                val userId = newMember()
                val created = AtomicReference<WishWithItem?>()
                try {
                    raceWithWithdrawal(pool, userId) {
                        created.set(wishPersistenceService.persist(userId, Item(link = ProductLink.parse("https://example.com/p$i"))))
                    }
                    val row = userRow(userId)
                    assertNotNull(row.deletedAt, "탈퇴가 관여했으면 종단은 tombstone (iter=$i)")
                    assertEquals(0, wishCount(userId), "tombstone 유저의 wish 행이 남으면 안 된다 (iter=$i)")
                } finally {
                    created.get()?.let {
                        jdbcTemplate.update("DELETE FROM wishes WHERE snapshot_id = ?", it.snapshot.getId())
                        jdbcTemplate.update("DELETE FROM item_snapshots WHERE id = ?", it.snapshot.getId())
                        jdbcTemplate.update("DELETE FROM items WHERE id = ?", it.item.getId())
                    }
                    cleanupUser(userId)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `탈퇴와 FCM 등록이 동시에 일어나도 종단적으로 tombstone 유저의 기기가 남지 않는다`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(ITERATIONS) { i ->
                val userId = newMember()
                try {
                    raceWithWithdrawal(pool, userId) {
                        userDeviceService.register(userId, "device-$i", "token-$userId-$i")
                    }
                    val row = userRow(userId)
                    assertNotNull(row.deletedAt, "탈퇴가 관여했으면 종단은 tombstone (iter=$i)")
                    assertEquals(emptyList(), userDeviceRepository.findAllByUserId(userId), "tombstone 유저의 기기 행이 남으면 안 된다 (iter=$i)")
                } finally {
                    cleanupUser(userId)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `탈퇴한 유저의 FCM 토큰 등록은 거부되고 기기가 생성되지 않는다`() {
        val userId = newMember()
        withdrawalService.withdraw(userId)
        try {
            val e = assertFailsWith<UserException> { userDeviceService.register(userId, "device-wd", "token-wd") }
            assertEquals(HttpStatus.CONFLICT, e.httpStatus, "탈퇴 유저 등록은 409(deletedUser)여야 한다")
            assertEquals(emptyList(), userDeviceRepository.findAllByUserId(userId), "tombstone 유저의 기기 행이 남으면 안 된다")
        } finally {
            cleanupUser(userId)
        }
    }

    companion object {
        // 고정 순열이 아니라 스케줄러 인터리빙에 의존하는 race 재현이므로, 창을 여러 번 두들겨 negative control 이
        // 잠금 부재 시 안정적으로 실패하도록 반복한다. fix 적용 상태에선 몇 회든 항상 tombstone 으로 수렴한다.
        private const val ITERATIONS = 20
    }
}
