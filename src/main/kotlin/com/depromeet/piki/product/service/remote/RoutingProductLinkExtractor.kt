package com.depromeet.piki.product.service.remote

import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.FallbackProductLinkExtractor
import com.depromeet.piki.product.service.ProductLinkExtractor
import com.depromeet.piki.product.service.ProductSnapshot
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

// 원격 추출 전환기의 진입점(strangler 스위치). product.extract.remote.enabled=true(정확히 이 문자열만 인식) 일 때만
// 뜨고 @Primary 로 워커의 ProductLinkExtractor 주입을 가로챈다. 이 키의 독자는 @ConditionalOnProperty 하나뿐이다 —
// properties 에 enabled 필드를 두지 않아 relaxed 바인딩과 조건 매칭이 갈라지는 분열("yes" 로 켰다고 믿는데 꺼져 있는)이 없다.
//
// off(기본)면 원격 빈들이 존재하지 않아 기존 진입점(FallbackProductLinkExtractor)이 유일 후보다 —
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
        // 전환기 롤아웃 관측용 — 어느 링크가 원격을 탔는지의 원장. embedded 제거(8단계) 때 함께 없어진다.
        log.info("extract route=remote url={}", link.safeLogString())
        return remote.extract(link)
    }

    // 점진 전환 게이트. 비어 있으면 아무것도 원격으로 가지 않고(안전 기본값 — enabled 만 켜고 목록을 깜빡해도
    // 컷오버가 터지지 않는다), 전량 전환은 ALL_HOSTS("*") 명시로만 연다. 도메인 단위 매칭(서브도메인 포함,
    // 대소문자·trailing dot 정규화)은 ProductLink.matchesAnyDomain 단일 술어가 진다 — 미지원 플랫폼 판정과 같은 규칙.
    // host 가 없는 기형 URI 는 어느 목록과도 매칭되지 않아 항상 embedded 로 남는다(설정 유무와 무관하게 일관).
    private fun routesToRemote(link: ProductLink): Boolean {
        val hosts = properties.normalizedHosts
        if (hosts.isEmpty()) return false
        if (hosts.contains(RemoteExtractionProperties.ALL_HOSTS)) return true
        return link.matchesAnyDomain(hosts)
    }
}
