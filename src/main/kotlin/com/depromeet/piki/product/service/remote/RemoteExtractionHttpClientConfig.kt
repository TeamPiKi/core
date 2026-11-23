package com.depromeet.piki.product.service.remote

import io.micrometer.observation.ObservationRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

// extractor 로 직접 거는 HTTP 호출용 RestClient 빈. 파싱은 스트림으로 오가고 모델 검증(ExtractionModelProbe)만 이 빈을 쓴다.
@Configuration(proxyBeanMethods = false)
class RemoteExtractionHttpClientConfig {
    // 정적 RestClient.builder() 를 쓴다 — 이 프로젝트의 Boot 4 구성엔 RestClient.Builder 빈이 자동 구성되지 않는다.
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
            .configureMessageConverters { it.registerDefaults().addCustomConverter(RemoteExtractionContract.messageConverter()) }
            .build()
    }
}
