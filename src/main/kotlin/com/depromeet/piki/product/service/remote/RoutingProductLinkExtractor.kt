package com.depromeet.piki.product.service.remote

import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.FallbackProductLinkExtractor
import com.depromeet.piki.product.service.ProductLinkExtractor
import com.depromeet.piki.product.service.ProductSnapshot
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

// 원격 추출 전환기의 진입점(strangler 스위치). product.extract.remote.enabled=true 일 때만 뜨고
// @Primary 로 워커의 ProductLinkExtractor 주입을 가로챈다.
//
// off(기본)면 이 빈 자체가 존재하지 않아 기존 진입점(FallbackProductLinkExtractor)이 유일 후보다 —
// 현행과 완전 동일하고, 통합 테스트의 StubProductLinkExtractor(@Primary) 구조와도 충돌하지 않는다
// (두 @Primary 가 공존하는 컨텍스트가 아예 만들어지지 않는다).
//
// 전환이 끝나면(이관 8단계) 이 라우팅과 embedded 경로(Fallback·전략 레이어·fetch/구조화/Gemini)를 함께 제거하고
// 원격 클라이언트를 단일 진입점으로 승격한다.
@Primary
@Component
@ConditionalOnProperty(prefix = "product.extract.remote", name = ["enabled"], havingValue = "true")
class RoutingProductLinkExtractor(
    private val remote: HttpProductLinkExtractor,
    private val embedded: FallbackProductLinkExtractor,
    private val properties: RemoteExtractionProperties,
) : ProductLinkExtractor {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun extract(link: ProductLink): ProductSnapshot {
        if (!routesToRemote(link)) return embedded.extract(link)
        log.info("extract route=remote url={}", link.safeLogString())
        return remote.extract(link)
    }

    // hosts 가 비어 있으면 전량 원격(enabled 가 이미 게이트), 있으면 도메인 단위 suffix 매칭(서브도메인 포함)만
    // 원격 — 점진 전환용. 매칭 규칙은 ProductLink.UNSUPPORTED_HOSTS 판정과 동일한 방식(부분 문자열이 아니라
    // 도메인 단위, trailing dot 제거)을 쓴다.
    private fun routesToRemote(link: ProductLink): Boolean {
        val hosts = properties.hosts
        if (hosts.isEmpty()) return true
        val host = link.value.host?.trimEnd('.')?.lowercase() ?: return false
        return hosts.any { host == it || host.endsWith(".$it") }
    }
}
