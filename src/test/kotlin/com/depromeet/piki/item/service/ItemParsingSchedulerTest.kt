package com.depromeet.piki.item.service

import com.depromeet.piki.product.service.remote.RemoteExtractionProperties
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class ItemParsingSchedulerTest {
    @Test
    fun `추출 호출 상한이 회수 기준(2분) 이상이면 부팅에서 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            ItemParsingScheduler.requireExtractionUnderStuck(RemoteExtractionProperties(baseUrl = "http://x", connectTimeoutMs = 2_000, readTimeoutMs = 118_000))
        }
    }

    // 기본값이나 계약 값이 회수 기준 이상으로 바뀌면 운영 부팅이 깨진다. 그 전에 여기서 걸린다
    @Test
    fun `기본 추출 호출 상한은 회수 기준보다 짧다`() {
        ItemParsingScheduler.requireExtractionUnderStuck(RemoteExtractionProperties(baseUrl = "http://x"))
    }
}
