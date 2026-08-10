package com.depromeet.piki.user.service

import com.depromeet.piki.user.domain.User
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 풀 소진 후 숫자 suffix 확장의 규칙 검증. 자릿수별 숫자 범위와 base 필터는 DB 없이 결정되는 순수 규칙이라
// 단위 테스트로 망라한다. 실제 발급(조회·충돌 회피)은 GuestNicknameGenerationIntegrationTest 가 본다.
class NicknameSuffixTest {
    @ParameterizedTest(name = "{0}자리 -> {1}..{2}")
    @CsvSource("1, 1, 9", "2, 10, 99", "3, 100, 999", "4, 1000, 9999")
    fun `자릿수 범위는 앞자리 0 없이 정확히 그 자릿수다`(
        width: Int,
        first: Int,
        last: Int,
    ) {
        val range = UserService.suffixRange(width)

        assertEquals(first, range.first)
        assertEquals(last, range.last)
    }

    @Test
    fun `자릿수 범위는 단계마다 겹치지 않는다`() {
        // 겹치면 이미 시도해 소진된 숫자를 다음 단계가 다시 뽑아 헛돈다.
        val ranges = (1..UserService.NICKNAME_SUFFIX_MAX_WIDTH).map { UserService.suffixRange(it) }

        ranges.zipWithNext { narrower, wider ->
            assertTrue(narrower.last < wider.first, "$narrower 와 $wider 가 겹친다")
        }
    }

    @Test
    fun `1자리는 풀 전체를 base 로 쓸 수 있다`() {
        // 최장 조합이 9자라 한 자리는 모든 base 에 붙는다.
        assertEquals(UserService.NICKNAME_POOL.size, UserService.basesFor(width = 1).size)
    }

    @ParameterizedTest(name = "{0}자리 base 는 전부 {0}자리를 붙여도 길이 제한 이하")
    @CsvSource("1", "2", "3", "4", "5", "6")
    fun `자릿수를 붙여도 길이 제한을 넘지 않는 base 만 남는다`(width: Int) {
        UserService.basesFor(width).forEach { base ->
            assertTrue(
                base.length + width <= User.NICKNAME_MAX_LENGTH,
                "'$base'(${base.length}자) + ${width}자리가 ${User.NICKNAME_MAX_LENGTH}자를 넘는다",
            )
        }
    }

    @Test
    fun `자릿수가 넓어질수록 쓸 수 있는 base 가 줄어든다`() {
        // 9자 조합 30개는 1자리까지만, 8자 148개는 2자리까지만 — 여유가 좁은 base 가 단계마다 빠진다.
        val sizes = (1..UserService.NICKNAME_SUFFIX_MAX_WIDTH).map { UserService.basesFor(it).size }

        sizes.zipWithNext { wider, narrower ->
            assertTrue(narrower <= wider, "자릿수가 늘었는데 base 가 늘었다: $sizes")
        }
        assertTrue(sizes.last() > 0, "최대 자릿수에도 쓸 수 있는 base 가 있어야 한다")
    }

    @Test
    fun `최대 자릿수는 최단 base 에 붙일 수 있는 자릿수다`() {
        val shortest = UserService.NICKNAME_POOL.minOf { it.length }

        assertEquals(User.NICKNAME_MAX_LENGTH - shortest, UserService.NICKNAME_SUFFIX_MAX_WIDTH)
    }
}
