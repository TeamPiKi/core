package com.depromeet.piki.admin.access

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// EnvironmentAccessFilter 게이트 경로 판정 단위 테스트(#733).
// 도메인 전체가 아니라 문서(/docs·/v3/api-docs)·actuator 만 게이트하고, 백엔드 API·grant 진입·health 는
// 통과함을 잠근다 — dev/staging 백엔드가 실수로 막히거나 문서가 새어나가는 회귀를 막는다.
class EnvironmentAccessFilterTest {
    @Test
    fun `문서와 actuator 경로만 게이트한다`() {
        assertTrue(EnvironmentAccessFilter.isGatedPath("/docs/index.html"))
        assertTrue(EnvironmentAccessFilter.isGatedPath("/v3/api-docs"))
        assertTrue(EnvironmentAccessFilter.isGatedPath("/actuator/loggers"))
    }

    @Test
    fun `백엔드 API·grant 진입·health 는 게이트하지 않는다`() {
        assertFalse(EnvironmentAccessFilter.isGatedPath("/api/v1/dev/users"))
        assertFalse(EnvironmentAccessFilter.isGatedPath("/api/v1/wishlists"))
        assertFalse(EnvironmentAccessFilter.isGatedPath("/admin-access/grant"))
        assertFalse(EnvironmentAccessFilter.isGatedPath("/health"))
    }

    @Test
    fun `루트 자신과 확장자 변형(spec yaml·json)도 게이트한다`() {
        // spec 은 /v3/api-docs 와 /v3/api-docs.yaml 양쪽에 서빙되므로 확장자 변형도 막아야 문서가 새지 않는다.
        assertTrue(EnvironmentAccessFilter.isGatedPath("/actuator"))
        assertTrue(EnvironmentAccessFilter.isGatedPath("/v3/api-docs.yaml"))
        assertTrue(EnvironmentAccessFilter.isGatedPath("/v3/api-docs/swagger-config"))
    }

    @Test
    fun `게이트 루트의 접두사만 겹치는 경로는 게이트하지 않는다`() {
        // startsWith 과매칭 방지 — 세그먼트 경계(/ 또는 .)가 아니면 게이트 대상이 아니다.
        assertFalse(EnvironmentAccessFilter.isGatedPath("/docs-private"))
        assertFalse(EnvironmentAccessFilter.isGatedPath("/v3/api-docs-extra"))
        assertFalse(EnvironmentAccessFilter.isGatedPath("/actuatorial"))
    }
}
