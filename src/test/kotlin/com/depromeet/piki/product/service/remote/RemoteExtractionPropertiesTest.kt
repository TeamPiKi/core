package com.depromeet.piki.product.service.remote

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// RemoteExtractionProperties 의 불변식(타임아웃 경계)을 주석이 아니라 테스트로 고정한다.
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
}
