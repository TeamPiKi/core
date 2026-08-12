package com.depromeet.piki.product.routing

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

// 정책 행의 불변식. 헤드리스 허가는 기본이 거부이고(default-deny), 허가 없는 브라우저 직행 행은 어느 경로로도
// 만들 수 없어야 한다 — 정상 흐름에선 입력 경계(AdminExtractionPolicyService)가 사용자 문구로 먼저 거르므로
// 여기 닿는 것은 그 경계를 빠뜨린 코드 버그다(그래서 require = 500).
class ExtractionPlatformPolicyEntityTest {
    @Test
    fun `헤드리스 허가는 지정하지 않으면 거부다 - 기본값이 곧 원장의 default-deny`() {
        val policy =
            ExtractionPlatformPolicyEntity(
                domain = "example.com",
                route = ExtractionRoute.SUPPORTED.name,
                reason = null,
            )

        assertFalse(policy.headlessAllowed)
        assertNull(policy.permissionRef)
        assertNull(policy.permissionGrantedAt)
    }

    @Test
    fun `허가 없이 HEADLESS_FIRST 정책 행은 만들 수 없다`() {
        assertFailsWith<IllegalArgumentException> {
            ExtractionPlatformPolicyEntity(
                domain = "example.com",
                route = ExtractionRoute.HEADLESS_FIRST.name,
                reason = null,
                headlessAllowed = false,
            )
        }
    }

    @Test
    fun `허가를 켜면 HEADLESS_FIRST 정책 행이 만들어진다`() {
        val policy =
            ExtractionPlatformPolicyEntity(
                domain = "example.com",
                route = ExtractionRoute.HEADLESS_FIRST.name,
                reason = "정적 fetch 전 UA 403",
                headlessAllowed = true,
                permissionRef = "partner mail thread",
            )

        assertEquals(ExtractionRoute.HEADLESS_FIRST.name, policy.route)
        assertEquals("partner mail thread", policy.permissionRef)
    }

    @Test
    fun `허가 없이도 HEADLESS_FIRST 가 아닌 정책은 만들어진다 - 허가는 브라우저 직행에만 걸리는 조건이다`() {
        // 허가를 요구하는 자리를 넓히면 차단(UNSUPPORTED) 기록조차 허가 없이는 못 남기게 된다.
        ExtractionRoute.entries
            .filter { it != ExtractionRoute.HEADLESS_FIRST }
            .forEach { route ->
                val policy = ExtractionPlatformPolicyEntity(domain = "example.com", route = route.name, reason = null)
                assertEquals(route.name, policy.route)
            }
    }
}
