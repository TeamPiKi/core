package com.depromeet.piki.product.service.remote

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// RemoteExtractionProperties 의 불변식(타임아웃 경계·hosts 정규화)을 주석이 아니라 테스트로 고정한다.
// read-timeout < stale(60s) 은 recover 유령 중복 발주를 막는 크로스모듈 계약이고, 0/음수 타임아웃은
// HttpURLConnection 에서 무한 대기라 워커 스레드를 영구 블록한다 — 둘 다 부팅에서 거부돼야 한다.
class RemoteExtractionPropertiesTest {
    @Test
    fun `read-timeout 이 stale 판정(60s) 이상이면 부팅에서 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            RemoteExtractionProperties(baseUrl = "http://x", readTimeoutMs = 60_000)
        }
        assertFailsWith<IllegalArgumentException> {
            RemoteExtractionProperties(baseUrl = "http://x", readTimeoutMs = 60_001)
        }
    }

    @Test
    fun `타임아웃이 0 이하이면 거부한다 - 0 은 무한 대기라 워커 스레드가 영구 블록된다`() {
        assertFailsWith<IllegalArgumentException> {
            RemoteExtractionProperties(baseUrl = "http://x", readTimeoutMs = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            RemoteExtractionProperties(baseUrl = "http://x", connectTimeoutMs = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            RemoteExtractionProperties(baseUrl = "http://x", readTimeoutMs = -1)
        }
    }

    @Test
    fun `stale 미만의 양수 타임아웃은 통과한다`() {
        val props = RemoteExtractionProperties(baseUrl = "http://x", connectTimeoutMs = 2_000, readTimeoutMs = 55_000)
        assertEquals(55_000, props.readTimeoutMs)
    }

    @Test
    fun `hosts 는 정규화된다 - 대소문자·trailing dot 제거, 공백 원소 제거, 와일드카드 보존`() {
        val props =
            RemoteExtractionProperties(
                baseUrl = "http://x",
                hosts = listOf("Musinsa.COM.", " 29cm.co.kr ", "", "*"),
            )

        assertEquals(listOf("musinsa.com", "29cm.co.kr", "*"), props.normalizedHosts)
    }

    @Test
    fun `hosts 가 비면 normalizedHosts 도 비어 아무것도 원격으로 가지 않는다`() {
        val props = RemoteExtractionProperties(baseUrl = "http://x", hosts = listOf("", "  "))
        assertTrue(props.normalizedHosts.isEmpty())
    }
}
