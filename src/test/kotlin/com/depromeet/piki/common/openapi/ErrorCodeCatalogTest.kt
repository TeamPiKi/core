package com.depromeet.piki.common.openapi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 겹2(코드 카탈로그)가 실제로 무엇을 렌더하는지, 그리고 등록된 code 가 유니크·형식 불변식을 지키는지
// Spring·Docker 없이 순수하게 검증한다.
class ErrorCodeCatalogTest {
    @Test
    fun `카탈로그는 운영 경로 ErrorCodeRegistry_all 로 code·HTTP·의미를 prefix(예외 클래스)별로 나열한다`() {
        // 운영 경로(ErrorCodeCatalogConfig 가 쓰는 registry)로 생성 — registry 에서 User 등록이 빠지면 아래 행 단언이 깨져 catalog 누락을 잡는다.
        val md = errorCodeCatalogMarkdown(ErrorCodeRegistry.all)

        assertTrue(md.contains("### USER"), md)
        assertTrue(md.contains("| USER-001 | 404 | 존재하지 않는 계정이에요. |"), md)
        assertTrue(md.contains("| USER-006 | 400 | 닉네임을 입력해 주세요. |"), md)
        assertTrue(md.contains("| USER-012 | 400 | 닉네임은 10자까지 입력할 수 있어요. |"), md)
    }

    @Test
    fun `등록된 모든 code 는 전역 유니크하다`() {
        val codes = ErrorCodeRegistry.all.map { it.code }

        assertEquals(codes.size, codes.toSet().size, "중복 code: ${codes.groupingBy { it }.eachCount().filter { it.value > 1 }}")
    }

    @Test
    fun `모든 code 는 PREFIX-000 형식이다`() {
        val format = Regex("^[A-Z_]+-\\d{3}$")

        assertTrue(ErrorCodeRegistry.all.all { format.matches(it.code) }, "형식 위반: ${ErrorCodeRegistry.all.map { it.code }.filterNot { format.matches(it) }}")
    }
}
