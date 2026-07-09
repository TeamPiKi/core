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
        // base-url 검증은 RemoteExtractionProperties.init 이 진다(검증 단일 지점 — 타임아웃 불변식과 같은 자리).
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
