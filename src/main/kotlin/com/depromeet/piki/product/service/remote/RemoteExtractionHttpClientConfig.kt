package com.depromeet.piki.product.service.remote

import io.micrometer.observation.ObservationRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

// 원격 추출 호출용 RestClient 빈. HttpProductLinkExtractor 안에서 직접 만들면 테스트가 가짜 응답을
// 끼울 수 없어 빈으로 분리한다. 링크(HttpProductLinkExtractor)·이미지(HttpImageSnapshotExtractor)가 공유한다.
@Configuration(proxyBeanMethods = false)
class RemoteExtractionHttpClientConfig {
    // 정적 RestClient.builder() 를 쓴다 — 이 프로젝트의 Boot 4 구성엔 RestClient.Builder 빈이 자동 구성되지 않는다.
    // ObservationRegistry 를 물려 원격 추출 호출이 item.parse trace 의 HTTP client span 으로 잡히고
    // traceparent 가 extractor 로 전파된다(extractor 서버 span 이 같은 trace 아래 연결).
    @Bean
    fun remoteExtractionRestClient(
        observationRegistry: ObservationRegistry,
        properties: RemoteExtractionProperties,
    ): RestClient {
        // 파싱의 유일 경로라 base-url 없인 모든 파싱이 연결 실패로 위장된다 — 부팅에서 즉시 드러낸다.
        require(properties.baseUrl.isNotBlank()) { "product.extract.remote.base-url 이 비어 있다 — 원격 추출은 유일한 파싱 경로다." }
        return RestClient
            .builder()
            .baseUrl(properties.baseUrl)
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(properties.connectTimeoutMs)
                    setReadTimeout(properties.readTimeoutMs)
                },
            ).observationRegistry(observationRegistry)
            .build()
    }
}
