package com.depromeet.piki.auth.infrastructure.oauth

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Test
import org.springframework.web.client.ResourceAccessException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OAuthRestClientTest {
    @Test
    fun `provider 호출은 연결에 실패해도 HTTP client observation 을 남긴다`() {
        val started = mutableListOf<String?>()
        val registry =
            ObservationRegistry.create().apply {
                observationConfig().observationHandler(
                    object : ObservationHandler<Observation.Context> {
                        override fun supportsContext(context: Observation.Context) = true

                        override fun onStart(context: Observation.Context) {
                            started.add(context.name)
                        }
                    },
                )
            }
        // 1번 포트는 열린 서비스가 없어 외부 호출 없이 곧바로 연결이 거부된다.
        val client = OAuthRestClient.create("http://127.0.0.1:1", registry)

        assertFailsWith<ResourceAccessException> {
            client.get().uri("/token").retrieve().toBodilessEntity()
        }

        assertEquals(listOf<String?>("http.client.requests"), started)
    }
}
