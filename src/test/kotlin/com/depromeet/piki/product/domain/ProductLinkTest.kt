package com.depromeet.piki.product.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductLinkTest {
    @Test
    fun `빈 문자열 또는 공백만 있으면 불변식 위반으로 거부한다`() {
        // 계약(ProductLinkException)이 아니라 불변식(require)이다 — 입력 경계의 @field:NotBlank 가 먼저 걸러
        // 정상 요청은 여기 닿지 않으므로 code 를 배정하지 않는다. 닿았다면 어느 경계가 검증을 빠뜨린 것.
        // message 는 캐치올 400 의 detail 로 나갈 수 있어 사용자 친화 문구를 유지한다.
        listOf("", "   ", "\t\n").forEach { raw ->
            val ex =
                assertFailsWith<IllegalArgumentException>("'$raw' 는 거부되어야 함") {
                    ProductLink.parse(raw)
                }
            assertEquals("링크를 입력해 주세요.", ex.message)
        }
    }

    @Test
    fun `https 가 아닌 scheme 은 모두 거부한다`() {
        val cases =
            listOf(
                "http://example.com",
                "ftp://example.com",
                "file:///etc/passwd",
                "javascript:alert(1)",
                "ws://example.com/socket",
            )
        cases.forEach { raw ->
            val ex =
                assertFailsWith<ProductLinkException>("$raw 는 거부되어야 함") {
                    ProductLink.parse(raw)
                }
            assertEquals("https 링크만 등록할 수 있어요.", ex.message)
        }
    }

    @Test
    fun `URI 파싱 자체가 실패하는 raw 는 형식 오류로 거부한다`() {
        // 예: data: URI 의 본문에 illegal character 가 들어가면 URI_create 단계에서 IllegalArgumentException
        val ex =
            assertFailsWith<ProductLinkException> {
                ProductLink.parse("data:text/html,<h1>x</h1>")
            }
        assertEquals("올바른 링크 형식이 아니에요. 다시 확인해 주세요.", ex.message)
    }

    @Test
    fun `예외 메시지에 원본 raw 는 포함되지 않는다`() {
        // GlobalExceptionHandler 가 message 를 응답 detail · 로그에 그대로 박는 구조라
        // 쿼리스트링에 섞일 수 있는 토큰 · 세션이 새지 않도록 message 단계에서 원본을 제거한다.
        val rawWithSecret = "data:text/html,<token=SHOULD_NOT_LEAK>"
        val ex =
            assertFailsWith<ProductLinkException> {
                ProductLink.parse(rawWithSecret)
            }
        assertEquals("올바른 링크 형식이 아니에요. 다시 확인해 주세요.", ex.message)
    }

    @Test
    fun `scheme 없이 호스트만 있는 raw 는 거부한다`() {
        val ex =
            assertFailsWith<ProductLinkException> {
                ProductLink.parse("example.com/product")
            }
        assertEquals("https 링크만 등록할 수 있어요.", ex.message)
    }

    @Test
    fun `대문자 scheme 도 case-insensitive 로 통과한다`() {
        val link = ProductLink.parse("HTTPS://shop.example.com/items/42")
        assertTrue(link.value.scheme.equals("https", ignoreCase = true))
    }

    @Test
    fun `포트 query fragment 가 포함된 URL 은 그대로 보존한 채 통과한다`() {
        val link = ProductLink.parse("https://shop.example.com:8443/p/42?ref=home&q=1#desc")

        assertEquals("shop.example.com", link.value.host)
        assertEquals(8443, link.value.port)
        assertEquals("/p/42", link.value.path)
        assertEquals("ref=home&q=1", link.value.query)
        assertEquals("desc", link.value.fragment)
    }

    @Test
    fun `앞뒤 공백을 제거한 채 parse 된다`() {
        val link = ProductLink.parse("  https://x.com/y  ")
        assertEquals("https://x.com/y", link.value.toString())
    }

    @Test
    fun `같은 raw 입력으로 parse 한 ProductLink 들은 동등하다`() {
        val a = ProductLink.parse("https://x.com/y")
        val b = ProductLink.parse("https://x.com/y")
        assertEquals(a, b)
    }

    @Test
    fun `toString 은 raw URL 을 그대로 노출한다`() {
        val raw = "https://shop.example.com/items/42"
        val link = ProductLink.parse(raw)
        assertEquals(raw, link.toString())
    }

    @Test
    fun `도메인 목록 매칭은 host 가 항목과 같거나 그 서브도메인일 때만 참이다`() {
        // 미지원 판정의 도메인 목록 자체는 DB 정책(extraction_platform_policies)으로 옮겨졌다 — 여기서는
        // 판정의 술어(matchesAnyDomain)가 우회를 막는 정규화·도메인 단위 매칭을 지키는지를 시드와 같은 목록으로 망라한다.
        val domains = setOf("kream.co.kr", "coupang.com", "naver.com", "naver.me", "oliveyoung.co.kr", "oy.run", "a-bly.com")
        val matched =
            listOf(
                "https://kream.co.kr/products/950123",
                "https://www.kream.co.kr/products/1",
                "https://api.kream.co.kr/x",
                "https://KREAM.co.kr/products/2", // host 대소문자 무관
                "https://www.coupang.com/vp/products/6584047366",
                "https://m.coupang.com/vm/products/7573479386",
                "https://search.shopping.naver.com/catalog/47788995857",
                "https://smartstore.naver.com/somestore/products/1",
                "https://naver.me/5AbCdEf", // 단축 링크 별도 도메인
                "https://naver.com./x", // trailing dot(절대 도메인 표기)로 우회 시도 — 정규화로 막혀야 한다
                "https://www.coupang.com./vp/products/1", // trailing dot
                "https://m.oliveyoung.co.kr/m/goods/getGoodsDetail.do?goodsNo=A000000213261",
                "https://oy.run/EiU6PnnqjxJeJ5",
                "https://applink.a-bly.com/1eso6m",
            )
        matched.forEach { raw ->
            assertTrue(ProductLink.parse(raw).matchesAnyDomain(domains), "$raw 는 매칭되어야 함")
        }

        val unmatched =
            listOf(
                "https://www.nike.com/kr/t/x",
                "https://shop.example.com/p/1",
                "https://notkream.co.kr/p", // 서브도메인이 아닌 다른 호스트(접두만 다름)
                "https://kream.co.kr.evil.com/p", // 접미사만 같은 다른 도메인(접미사 매칭 우회 방지)
                "https://coupang.com.evil.com/p",
                "https://navermart.com/p", // 'naver' 부분문자열이지만 naver.com 도메인이 아니므로 불일치(도메인 단위 매칭)
                "https://oy.run.evil.com/p",
            )
        unmatched.forEach { raw ->
            assertFalse(ProductLink.parse(raw).matchesAnyDomain(domains), "$raw 는 매칭되지 않아야 함")
        }
    }

    @Test
    fun `미지원 플랫폼 URL 도 parse 자체는 성공한다 - 저장된 행 읽기가 깨지지 않도록`() {
        // 미지원 판정(ExtractionRoutingPolicy)은 등록 입력 경계 전용이고, parse(형식 불변식)는 컨버터·redirect 와 공유된다.
        // 미지원 검증을 parse 에 넣으면 이미 저장된 미지원 URL 읽기가 깨지므로, parse 는 미지원이어도 통과해야 한다.
        val link = ProductLink.parse("https://kream.co.kr/products/950123")
        assertEquals("kream.co.kr", link.value.host)
    }
}
