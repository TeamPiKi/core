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

        // 공통(횡단) code 도 registry 에 등록돼 카탈로그에 나열된다 — 4xx 구체 code 와 5xx 재시도 방식별 code.
        assertTrue(md.contains("### COMMON"), md)
        assertTrue(md.contains("| COMMON-UNAUTHORIZED | 401 | 로그인이 필요해요. |"), md)
        assertTrue(md.contains("| COMMON-INVALID-INPUT | 400 | 요청 값을 다시 확인해 주세요. |"), md)
        assertTrue(md.contains("| COMMON-RETRYABLE | 502 | 일시적인 오류예요. 잠시 후 다시 시도해 주세요. |"), md)
        assertTrue(md.contains("| COMMON-SERVER-BUSY | 503 | 지금 요청이 많아요. 잠시 후 다시 시도해 주세요. |"), md)
        assertTrue(md.contains("| COMMON-SERVER-ERROR | 500 | 서버에 문제가 발생했어요. 불편을 드려 죄송해요. |"), md)
    }

    @Test
    fun `등록된 모든 code 는 전역 유니크하다`() {
        val codes = ErrorCodeRegistry.all.map { it.code }

        assertEquals(codes.size, codes.toSet().size, "중복 code: ${codes.groupingBy { it }.eachCount().filter { it.value > 1 }}")
    }

    @Test
    fun `모든 code 는 PREFIX-SUFFIX 형식이다 (도메인은 숫자 3자리, 공통은 의미 문자열)`() {
        // 도메인 code 는 숫자 append-only(USER-001), 공통 code 는 FE 계약상 의미 문자열(COMMON-UNAUTHORIZED·
        // COMMON-METHOD-NOT-ALLOWED). prefix(substringBefore("-"))로 그룹핑되므로 prefix 는 [A-Z]+ 로 고정하고,
        // suffix 는 숫자 3자리 또는 하이픈으로 이어진 대문자 단어들 중 하나를 허용한다.
        val format = Regex("^[A-Z]+-(\\d{3}|[A-Z]+(-[A-Z]+)*)$")

        assertTrue(ErrorCodeRegistry.all.all { format.matches(it.code) }, "형식 위반: ${ErrorCodeRegistry.all.map { it.code }.filterNot { format.matches(it) }}")
    }
}
