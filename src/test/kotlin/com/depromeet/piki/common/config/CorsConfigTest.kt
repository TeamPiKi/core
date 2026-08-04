package com.depromeet.piki.common.config

import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// CORS 의 프로파일 분기(LAN 사설망 허용)를 Spring 컨텍스트 없이 단위로 검증한다(#509).
// 통합 테스트는 비-prod 컨텍스트라 "prod 에선 LAN 이 안 열린다"를 못 보므로, 여기서 prod/비-prod 두 분기를 직접 단언한다.
class CorsConfigTest {
    private fun patternsFor(vararg activeProfiles: String): List<String>? {
        val env = MockEnvironment().apply { setActiveProfiles(*activeProfiles) }
        val source = CorsConfig(env).corsConfigurationSource() as UrlBasedCorsConfigurationSource
        return source.getCorsConfigurations()["/**"]!!.allowedOriginPatterns
    }

    @Test
    fun `prod 프로파일에서는 LAN 사설망 패턴을 열지 않는다`() {
        assertEquals(null, patternsFor("prod"))
    }

    @Test
    fun `dev 프로파일에서는 192_168 LAN 패턴만 허용한다`() {
        val patterns = patternsFor("dev")
        assertEquals(listOf("http://192.168.*.*:[*]"), patterns) // 192.168 만 — 10·172 등 넓은 대역은 넣지 않는다
    }

    @Test
    fun `프로파일 미지정(로컬)에서도 192_168 LAN 패턴을 허용한다`() {
        // 로컬 실행은 active profile 이 비어있을 수 있다 — prod 가 아니므로 LAN 이 열려야 한다.
        assertEquals(listOf("http://192.168.*.*:[*]"), patternsFor())
    }
}
