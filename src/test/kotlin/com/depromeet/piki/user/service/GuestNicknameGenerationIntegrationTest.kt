package com.depromeet.piki.user.service

import com.depromeet.piki.support.IntegrationTestSupport
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
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

    @Test
    fun `게스트를 여러 번 생성하면 풀 안에서 서로 겹치지 않는 닉네임이 발급된다`() {
        val pool = UserService.NICKNAME_POOL.toSet()

        val nicknames = (1..30).map { userService.createGuest().nickname }

        assertEquals(nicknames.size, nicknames.toSet().size, "발급된 닉네임에 중복이 없어야 한다")
        assertTrue(nicknames.all { it in pool }, "모든 닉네임이 NICKNAME_POOL 안에 있어야 한다")
    }
}
