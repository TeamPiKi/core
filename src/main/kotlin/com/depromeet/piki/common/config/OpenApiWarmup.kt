package com.depromeet.piki.common.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

// dev 문서 노출 시(docs.enabled=true), 앱 기동 직후 OpenAPI spec 을 한 번 미리 생성해 springdoc 캐시를 데운다.
// springdoc 은 첫 /v3/api-docs 요청에서 lazy 로 spec 을 만드는데(OpenApiConfig 의 커스터마이저 + 16개 *ApiExamples
// OperationCustomizer 실행) 이 콜드 비용이 수백 ms~초 단위다. dev 는 배포마다 앱이 재기동돼 그 비용이 매번
// "배포 후 문서를 처음 여는 개발자" 에게 떨어진다. 기동 시 self-GET 으로 미리 캐시해 첫 로딩 콜드를 부팅으로 옮긴다.
//
// docs.enabled 게이트(WebConfig 와 동일)로 문서 비노출 환경(staging/prod)에선 빈 자체가 안 떠 self-GET 이 없다.
// ApplicationReadyEvent 시점이라 DispatcherServlet 이 준비돼 있고, 실패는 비치명(warm-up 은 best-effort — 문서
// 자체는 첫 요청에서 여전히 lazy 로 뜬다)이라 runCatching 으로 삼켜 기동을 막지 않는다.
@Component
@ConditionalOnProperty(name = ["docs.enabled"], havingValue = "true", matchIfMissing = false)
class OpenApiWarmup(
    private val environment: Environment,
    @param:Value("\${springdoc.api-docs.path:/v3/api-docs}") private val apiDocsPath: String,
) : ApplicationListener<ApplicationReadyEvent> {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        // local.server.port 는 Boot 이 실제 WebServer 기동 시에만 Environment 에 넣는다. 없으면 서블릿 컨테이너가
        // 안 뜬 컨텍스트(webEnvironment=MOCK 통합테스트 등)라 self-GET 이 무의미하므로 조용히 생략한다.
        val port =
            environment.getProperty("local.server.port") ?: run {
                log.debug("WebServer 미기동(local.server.port 없음) — OpenAPI warm-up 생략")
                return
            }
        runCatching {
            RestClient
                .create()
                .get()
                .uri("http://localhost:$port$apiDocsPath")
                .retrieve()
                .toBodilessEntity()
        }.onSuccess { log.info("OpenAPI spec warm-up 완료 (port={}, path={})", port, apiDocsPath) }
            .onFailure { log.warn("OpenAPI spec warm-up 실패(무시) path={} cause={}", apiDocsPath, it.message) }
    }
}
