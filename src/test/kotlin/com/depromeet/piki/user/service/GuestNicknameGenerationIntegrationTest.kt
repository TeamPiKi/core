package com.depromeet.piki.user.service

import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.User
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 게스트 닉네임 생성의 불변식 회귀 가드: 반복 생성 시 풀 안에서 서로 겹치지 않는 닉네임이 발급된다.
// 이 불변식은 IN(4096) 전수 조회든 IN(64) subset 조회든 동일하게 성립해야 하므로, 조회 방식 리팩터(#685)의
// characterization 테스트로 둔다. createGuest 는 비트랜잭션이나 saveAndFlush 가 이 테스트의 트랜잭션에
// 참여해 발급 닉네임의 unique 제약이 실제로 적용되고, 종료 시 롤백된다.
@Transactional
class GuestNicknameGenerationIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var userService: UserService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `게스트를 여러 번 생성하면 풀 안에서 서로 겹치지 않는 닉네임이 발급된다`() {
        val pool = UserService.NICKNAME_POOL.toSet()

        val nicknames = (1..30).map { userService.createGuest().nickname }

        assertEquals(nicknames.size, nicknames.toSet().size, "발급된 닉네임에 중복이 없어야 한다")
        assertTrue(nicknames.all { it in pool }, "모든 닉네임이 NICKNAME_POOL 안에 있어야 한다")
    }

    // 경계값: 64개 subset 이 전부 taken 인 near-exhaustion 에서, 샘플 고갈을 풀 고갈로 오인하지 않는다(#760 CodeRabbit).
    // 풀에 닉네임을 1개만 남기면 subset(64)은 사실상 전부 taken 이라 fallback 전체 조회 경로를 태운다.
    @Test
    fun `풀이 소진 직전이면 남은 닉네임을 발급하고 완전 소진되면 숫자를 붙여 확장한다`() {
        val pool = UserService.NICKNAME_POOL
        val onlyFree = pool.first()
        occupy(pool - onlyFree)

        // subset 이 전부 taken 이어도 fallback 전체 조회로 유일하게 남은 onlyFree 를 찾아 발급한다.
        val issued = userService.createGuest()
        assertEquals(onlyFree, issued.nickname, "풀에 하나 남은 닉네임이 발급돼야 한다")

        // 위 createGuest 로 onlyFree 까지 점유돼 풀이 완전 소진 → 숫자 suffix 확장 경로로 넘어간다(#920).
        val expanded = userService.createGuest().nickname
        assertTrue(expanded !in pool.toSet(), "'$expanded' 는 풀 밖(숫자 확장)이어야 한다")
        assertTrue(expanded.length <= User.NICKNAME_MAX_LENGTH, "'$expanded'(${expanded.length}자)가 길이 제한을 넘는다")

        // "{풀 조합}{숫자}" 형태 — base 는 풀 안에 있고 나머지는 앞자리 0 없는 숫자다.
        val base = expanded.dropLastWhile { it.isDigit() }
        val suffix = expanded.substring(base.length)
        assertTrue(base in pool.toSet(), "base '$base' 가 풀 안에 있어야 한다")
        assertTrue(suffix.isNotEmpty() && !suffix.startsWith("0"), "suffix '$suffix' 는 앞자리 0 없는 숫자여야 한다")
    }

    @Test
    fun `확장 구간에서도 발급 닉네임은 서로 겹치지 않는다`() {
        val pool = UserService.NICKNAME_POOL
        // 여기선 풀을 하나도 남기지 않는다 — 하나라도 비면 첫 발급이 그 조합을 가져가 확장 경로를 안 탄다.
        occupy(pool)

        // 풀이 완전히 소진된 상태에서 연속 발급 — 전부 확장 경로를 탄다.
        val issued = (1..20).map { userService.createGuest().nickname }

        assertEquals(issued.size, issued.toSet().size, "확장 구간에서도 중복이 없어야 한다")
        assertTrue(issued.none { it in pool.toSet() }, "모두 풀 밖(숫자 확장)이어야 한다")
    }

    // 점유는 JPA 개별 save 대신 batch INSERT 로 넣는다 — 4096행이라 왕복 비용이 테스트 시간을 지배한다.
    // 클래스 레벨 @Transactional 안에서 같은 커넥션을 쓰므로 이 행들도 함께 롤백되고, createGuest 의
    // 닉네임 조회는 flush 후 같은 트랜잭션을 읽어 점유가 그대로 보인다.
    private fun occupy(nicknames: List<String>) {
        val now = Timestamp.valueOf(LocalDateTime.now())
        jdbcTemplate.batchUpdate(
            "INSERT INTO users (id, nickname, profile_image, identity_type, created_at, updated_at) VALUES (?, ?, ?, 'GUEST', ?, ?)",
            nicknames.map { arrayOf<Any>(uuidToBytes(UUID.randomUUID()), it, "https://example.test/avatar", now, now) },
        )
    }
}
