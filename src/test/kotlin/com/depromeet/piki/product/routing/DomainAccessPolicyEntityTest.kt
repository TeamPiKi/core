package com.depromeet.piki.product.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

// 엔티티 불변식 — 어떤 경로가 만들든(백오피스·테스트·미래의 배치) 같은 결과가 나오게 도메인이 자기방어하는 층.
// 정상 흐름에선 입력 경계가 먼저 사용자 문구로 거르므로 여기 닿으면 그 경계가 검증을 빠뜨린 코드 버그다.
class DomainAccessPolicyEntityTest {
    @Test
    fun `차단 정책은 근거 없이도 만들어진다 - 차단은 우리 관측이라 상대의 허락이 필요 없다`() {
        val policy =
            DomainAccessPolicyEntity(
                domain = "coupang.com",
                access = DomainAccess.BLOCKED.name,
                reason = "403 봇 차단",
            )

        assertEquals(DomainAccess.BLOCKED.name, policy.access)
        assertNull(policy.permissionRef)
    }

    @Test
    fun `근거 없이 허락 정책 행은 만들 수 없다`() {
        // 이 값은 적극적인 수단을 여는 값이라, 근거 없이 켜지면 원장이 "왜 열려 있나"에 답하지 못한다.
        assertFailsWith<IllegalArgumentException> {
            DomainAccessPolicyEntity(
                domain = "example.com",
                access = DomainAccess.ALLOWED.name,
                reason = "제휴 완료",
            )
        }
    }

    @Test
    fun `근거를 남기면 허락 정책 행이 만들어진다`() {
        val policy =
            DomainAccessPolicyEntity(
                domain = "example.com",
                access = DomainAccess.ALLOWED.name,
                reason = "제휴 담당자 회신",
                permissionRef = "partner mail thread",
            )

        assertEquals(DomainAccess.ALLOWED.name, policy.access)
        assertEquals("partner mail thread", policy.permissionRef)
    }

    @Test
    fun `공백뿐인 근거는 근거로 치지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            DomainAccessPolicyEntity(
                domain = "example.com",
                access = DomainAccess.ALLOWED.name,
                reason = null,
                permissionRef = "   ",
            )
        }
    }

    @Test
    fun `도메인은 정규형이어야 한다`() {
        assertFailsWith<IllegalArgumentException> {
            DomainAccessPolicyEntity(domain = "Coupang.com.", access = DomainAccess.BLOCKED.name, reason = null)
        }
    }

    @Test
    fun `이 바이너리가 모르는 값도 행으로는 만들어진다 - 읽는 쪽이 tolerant 하게 진다`() {
        // @Enumerated 로 두면 모르는 값 한 행이 findAll 하이드레이션을 깨 부팅이 죽는다(구버전 롤백 함정).
        val policy = DomainAccessPolicyEntity(domain = "example.com", access = "FUTURE_VALUE", reason = null)

        assertEquals("FUTURE_VALUE", policy.access)
    }
}
