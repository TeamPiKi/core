package com.depromeet.piki.product.service.remote

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemoteExtractionPropertiesTest {
    @Test
    fun `base-url 이 비어 있으면 부팅에서 거부한다 - 원격 추출은 유일한 파싱 경로다`() {
        assertFailsWith<IllegalArgumentException> { RemoteExtractionProperties(baseUrl = "") }
        assertFailsWith<IllegalArgumentException> { RemoteExtractionProperties(baseUrl = "  ") }
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
    fun `read-timeout 기본값은 계약의 호출자 타임아웃이고 그 값으로 생성된다`() {
        val props = RemoteExtractionProperties(baseUrl = "http://x")
        assertEquals(ExtractionEndpoints.CALLER_READ_TIMEOUT_MS, props.readTimeoutMs)
    }

    @Test
    fun `양수 타임아웃은 그대로 받는다`() {
        val props = RemoteExtractionProperties(baseUrl = "http://x", connectTimeoutMs = 2_000, readTimeoutMs = 55_000)
        assertEquals(55_000, props.readTimeoutMs)
    }
}
