package com.depromeet.piki.product.service.remote

import io.micrometer.observation.ObservationRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

// 원격 추출 호출용 RestClient 빈. HttpProductLinkExtractor 안에서 직접 만들면 테스트가 가짜 응답을
// 끼울 수 없어(PageFetchHttpClientConfig 와 같은 이유) 빈으로 분리한다.
// 원격 서브시스템 전체(@ConditionalOnProperty 3형제: 이 빈·HttpProductLinkExtractor·RoutingProductLinkExtractor)가
// 같은 키로 함께 뜨고 함께 꺼진다 — off 면 RestClient 빈도 안 만들어져 기존 pageFetchRestClient 단독 상태와 동일하다.
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "product.extract.remote", name = ["enabled"], havingValue = "true")
class RemoteExtractionHttpClientConfig {
    // 정적 RestClient.builder() 를 쓴다 — 이 프로젝트의 Boot 4 구성엔 RestClient.Builder 빈이 자동 구성되지 않는다
    // (pageFetchRestClient·GeminiHttpClient 와 동일). ObservationRegistry 를 물려 원격 추출 호출이 item.parse
    // trace 의 HTTP client span 으로 잡히고 traceparent 가 extractor 로 전파된다(extractor 서버 span 이 같은 trace 아래 연결).
    @Bean
    fun remoteExtractionRestClient(
        observationRegistry: ObservationRegistry,
        properties: RemoteExtractionProperties,
    ): RestClient {
        // 스위치가 켜져 이 빈이 실제로 뜨는 시점의 계약 검증 — base-url 없이 켜면 부팅에서 즉시 드러난다.
        require(properties.baseUrl.isNotBlank()) { "product.extract.remote.enabled=true 면 base-url 이 필요하다." }
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
