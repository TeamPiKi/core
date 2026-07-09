package com.depromeet.piki.auth.infrastructure.oauth

import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

// OAuth provider 호출용 RestClient 팩토리. 외부 호출(토큰 교환·userinfo)이 늘어져 요청 스레드를
// 무한정 잡는 것을 막기 위해 connect/read 타임아웃을 둔다. OAuth 응답은 빠른 게 정상이라 짧게 잡는다.
// (connect/read 타임아웃을 직접 걸려고 SimpleClientHttpRequestFactory 를 쓴다.)
object OAuthRestClient {
    private val CONNECT_TIMEOUT = Duration.ofSeconds(5)
    private val READ_TIMEOUT = Duration.ofSeconds(10)

    fun create(baseUrl: String): RestClient =
        RestClient
            .builder()
            .baseUrl(baseUrl)
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(CONNECT_TIMEOUT)
                    setReadTimeout(READ_TIMEOUT)
                },
            ).build()
}
