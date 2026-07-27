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

        // auth 도메인 이관(#762) — AUTH·OAUTH·APPLE 3 prefix 가 registry 에 등록돼 나열된다.
        assertTrue(md.contains("### AUTH"), md)
        assertTrue(md.contains("| AUTH-001 | 401 | 로그인 정보가 만료됐어요. 다시 로그인해 주세요. |"), md)
        assertTrue(md.contains("### OAUTH"), md)
        assertTrue(md.contains("| OAUTH-001 | 502 | 로그인에 실패했어요. 잠시 후 다시 시도해 주세요. |"), md)
        // OAUTH-007 misconfigured 는 502→500 교정: SERVER_ERROR category 라 status 가 500 으로 파생된다.
        assertTrue(md.contains("| OAUTH-007 | 500 | 로그인에 실패했어요. 잠시 후 다시 시도해 주세요. |"), md)
        assertTrue(md.contains("### APPLE"), md)
        assertTrue(md.contains("| APPLE-001 | 401 | 유효하지 않은 Apple 서버 알림입니다. |"), md)

        // notification·announcement 도메인 이관(#763) — 커서 문구는 wish·announcement 와 통일된 사용자 친화 문구.
        assertTrue(md.contains("### NOTIFICATION"), md)
        assertTrue(md.contains("| NOTIFICATION-001 | 400 | 페이지를 불러오지 못했어요. 새로고침 해주세요. |"), md)
        assertTrue(md.contains("### ANNOUNCEMENT"), md)
        assertTrue(md.contains("| ANNOUNCEMENT-001 | 404 | 존재하지 않는 공지예요. |"), md)
        assertTrue(md.contains("| ANNOUNCEMENT-002 | 400 | 페이지를 불러오지 못했어요. 새로고침 해주세요. |"), md)

        // tournament 도메인 이관(#764) — 33개. 이 도메인부터 409(CONFLICT) 응답이 처음으로 도메인 code 를 싣는다
        // (#744 는 CONFLICT 공통 code 를 두지 않아 그동안 code=null 이었다). 4xx·403·404·409 를 대표로 단언한다.
        assertTrue(md.contains("### TOURNAMENT"), md)
        assertTrue(md.contains("| TOURNAMENT-001 | 403 | 이 토너먼트에 접근할 수 없어요. |"), md)
        assertTrue(md.contains("| TOURNAMENT-002 | 404 | 토너먼트를 찾을 수 없어요. 이미 삭제됐을 수 있어요. |"), md)
        assertTrue(md.contains("| TOURNAMENT-005 | 409 | 토너먼트가 시작되기 전에만 할 수 있어요. |"), md)
        assertTrue(md.contains("| TOURNAMENT-033 | 400 | 조회 개수는 1 이상이어야 해요. |"), md)

        // wish 도메인 이관(#797) — 8개. 403·400·404·409 를 대표로 단언한다.
        assertTrue(md.contains("### WISH"), md)
        assertTrue(md.contains("| WISH-001 | 403 | 위시리스트는 회원만 이용할 수 있어요. |"), md)
        assertTrue(md.contains("| WISH-003 | 400 | 페이지를 불러오지 못했어요. 새로고침 해주세요. |"), md)
        assertTrue(md.contains("| WISH-004 | 404 | 이미 삭제된 아이템이에요. |"), md)
        assertTrue(md.contains("| WISH-008 | 409 | 추출에 실패한 항목은 새로고침 대신 정보를 직접 입력해 복구해 주세요. |"), md)

        // item 도메인 이관(#798) — 5개. 전부 FAILED 항목 보정(위시·토너먼트 아이템 공용 경로)에서 나온다.
        // 다른 도메인은 개수가 많아 대표만 단언하지만, item 은 5개뿐이라 전량 단언해 결번 없이 고정한다.
        assertTrue(md.contains("### ITEM"), md)
        assertTrue(md.contains("| ITEM-001 | 409 | 이미 등록된 상품은 수정할 수 없어요. |"), md)
        assertTrue(md.contains("| ITEM-002 | 409 | 상품 정보를 가져오는 중이에요. 잠시만 기다려 주세요. |"), md)
        assertTrue(md.contains("| ITEM-003 | 400 | 상품 이름을 입력해 주세요. |"), md)
        assertTrue(md.contains("| ITEM-004 | 400 | 상품 가격을 입력해 주세요. |"), md)
        assertTrue(md.contains("| ITEM-005 | 400 | 상품 이미지를 등록해 주세요. |"), md)
    }

    @Test
    fun `AnnouncementImage code 는 공개 카탈로그에 등록되지 않는다 (어드민 SSR 전용)`() {
        // AnnouncementImageException 은 어드민 백오피스(Thymeleaf SSR)에서 자체 catch 돼 리다이렉트로 처리되고
        // GlobalExceptionHandler 를 거치지 않아 wire code 로 나가지 않는다. 따라서 클라 대면 공개 카탈로그에
        // 넣으면 안 된다 — 향후 실수로 ErrorCodeRegistry 에 등록되는 회귀를 막는 가드.
        val md = errorCodeCatalogMarkdown(ErrorCodeRegistry.all)

        assertTrue(ErrorCodeRegistry.all.none { it.code.startsWith("ANNOUNCEMENT-IMAGE-") }, "AnnouncementImage code 가 registry 에 새어들어옴")
        assertTrue(!md.contains("ANNOUNCEMENT-IMAGE-"), md)
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
