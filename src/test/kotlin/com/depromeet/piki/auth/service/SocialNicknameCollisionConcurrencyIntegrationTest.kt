package com.depromeet.piki.auth.service

import com.depromeet.piki.auth.infrastructure.oauth.OAuthProvider
import com.depromeet.piki.auth.infrastructure.oauth.OAuthUserInfo
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.service.UserService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 소셜 신규 가입의 닉네임 race 재시도 검증(#920).
//
// 닉네임은 자동 생성이라 '중복'이 사용자 입력 오류가 아니고, 생성과 저장 사이 race 로만 충돌한다.
// 게스트 생성은 예전부터 재시도로 이를 흡수했지만 소셜 가입엔 그 방어가 없었고, 더 나쁘게는 그 충돌이
// SocialAccountService 에서 '소셜 선점 충돌'로 오진돼 loginExisting 이 null 을 반환하며 500 으로 샜다.
//
// 이 테스트는 그 fix 의 negative control 이다: SocialAccountService 의 재시도(createSocialUserAndLinkRetryingNickname)를
// 걷어내고 socialAccountWriter.createSocialUserAndLink 직접 호출로 되돌리면, 마지막 하나를 동시에 노린
// 스레드들이 닉네임 충돌 → loginExisting(null) → 원본 예외로 떨어져 아래 '예외 없음' 단언이 깨진다.
//
// @Transactional 자동 롤백을 쓰지 않는다 — 별도 트랜잭션이 각자 커밋돼야 unique 충돌이 재현된다.
// 풀 전체를 점유하므로 만든 행은 끝에서 직접 정리한다. (CLAUDE.md "동시성·시간 의존 통합 테스트" 분류)
class SocialNicknameCollisionConcurrencyIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var socialAccountService: SocialAccountService

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `풀에 하나만 남은 상태에서 소셜 동시 가입해도 전원 성공하고 닉네임이 겹치지 않는다`() {
        val pool = UserService.NICKNAME_POOL
        val onlyFree = pool.first()
        val socialIdPrefix = "nick-race-${UUID.randomUUID()}"
        // 마지막 하나만 남기고 점유 — 동시 요청이 모두 그 하나를 노려 충돌이 결정적으로 재현된다.
        // (풀에 여유가 많으면 서로 다른 닉네임을 뽑아 race 가 안 일어난다.)
        occupyAll(pool - onlyFree)

        val threadCount = 4
        val executor = Executors.newFixedThreadPool(threadCount)
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val nicknames = Collections.synchronizedList(mutableListOf<String>())
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())

        try {
            repeat(threadCount) { i ->
                executor.submit {
                    // socialId 를 스레드마다 다르게 둬 소셜 선점 충돌을 배제한다 — 여기서 보려는 건 닉네임 충돌뿐이다.
                    val userInfo = OAuthUserInfo(OAuthProvider.GOOGLE, "$socialIdPrefix-$i", null)
                    ready.countDown()
                    start.await()
                    runCatching { socialAccountService.resolveUser(userInfo, null).nickname }
                        .onSuccess { nicknames.add(it) }
                        .onFailure { errors.add(it) }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "모든 스레드가 시작 게이트에 준비돼야 한다")
            start.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "동시 작업이 시간 내 끝나야 한다")

            assertEquals(emptyList(), errors.map { "${it::class.simpleName}: ${it.message}" }, "닉네임 race 는 재시도로 흡수돼야 한다")
            assertEquals(threadCount, nicknames.size, "모든 요청이 가입에 성공해야 한다")
            assertEquals(nicknames.size, nicknames.toSet().size, "발급 닉네임이 서로 겹치면 안 된다")
            // 하나는 마지막 남은 조합을, 나머지는 숫자 확장을 받는다.
            assertTrue(onlyFree in nicknames, "남은 조합 '$onlyFree' 이 누군가에게 발급돼야 한다")
            assertTrue(nicknames.any { it !in pool.toSet() }, "충돌한 요청은 숫자 확장 닉네임을 받아야 한다")
        } finally {
            // 정리보다 워커 종료가 먼저다. 위 단언이 깨져 이 블록에 들어온 경우 워커가 아직 살아 있을 수 있고,
            // 살아남은 워커가 정리 쿼리 뒤에 커밋하면 그 행이 uq_users_nickname 을 물고 남아 이후 실행을 깨뜨린다.
            // start 를 먼저 열어 게이트에 갇힌 워커를 풀어 준다 — ready 단언이 깨진 경로에선 countDown 이 안 돌아
            // 워커 4개가 non-daemon 스레드로 영구 대기하고, 그대로면 테스트 JVM 이 종료하지 못한다.
            // (CountDownLatch 는 0 에서 더 안 내려가고 종료된 executor 의 shutdownNow 도 무해해, 정상 경로엔 영향이 없다.)
            start.countDown()
            executor.shutdownNow()
            executor.awaitTermination(30, TimeUnit.SECONDS)
            jdbcTemplate.update(
                "DELETE FROM users WHERE id IN (SELECT user_id FROM user_details WHERE social_id LIKE ?)",
                "$socialIdPrefix%",
            )
            jdbcTemplate.update("DELETE FROM user_details WHERE social_id LIKE ?", "$socialIdPrefix%")
            releaseAll()
        }
    }

    // 풀 점유는 JPA 개별 save 대신 batch INSERT 로 넣는다 — 4095행이라 왕복 비용이 테스트 시간을 지배한다.
    private fun occupyAll(nicknames: List<String>) {
        val now = Timestamp.valueOf(LocalDateTime.now())
        jdbcTemplate.batchUpdate(
            "INSERT INTO users (id, nickname, profile_image, identity_type, created_at, updated_at) VALUES (?, ?, ?, 'GUEST', ?, ?)",
            nicknames.map { arrayOf<Any>(uuidToBytes(UUID.randomUUID()), it, OCCUPIED_PROFILE_IMAGE, now, now) },
        )
    }

    // 점유 행은 프로필 이미지 마커로 지운다 — 닉네임 IN(4096) 삭제보다 짧고, 확장 닉네임까지 한 번에 걸린다.
    private fun releaseAll() {
        jdbcTemplate.update("DELETE FROM users WHERE profile_image = ?", OCCUPIED_PROFILE_IMAGE)
    }

    companion object {
        // 이 테스트가 만든 점유 행만 골라 지우기 위한 마커. 다른 테스트·시드 데이터와 겹치지 않는 값이어야 한다.
        private const val OCCUPIED_PROFILE_IMAGE = "https://example.test/nickname-race-occupied.png"
    }
}
