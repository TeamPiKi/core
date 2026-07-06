package com.depromeet.piki.product.routing

import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.domain.ProductLinkException
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

// 플랫폼(host)별 추출 라우팅의 단일 결정 지점(디스패처). "이 링크를 어떻게 다룰까"의 host 축 정책이 전부 여기로
// 모인다 — 등록 경계의 미지원 거절(UNSUPPORTED)과 추출 체인의 브라우저 직행(HEADLESS_FIRST).
// 인터페이스/구현 분리는 알림의 NotificationTemplateProvider 와 같은 구조 — 소비자(등록 경계·Fallback)의 단위
// 테스트가 DB 없이 정책을 대체할 수 있게 한다.
//
// 원격 라우팅(EXTRACT_REMOTE_HOSTS)은 여기 합치지 않는다 — 그건 "어떻게 추출하나"가 아니라 전환기(strangler)의
// "어디서 추출하나" 인프라 축이라 배포 파이프라인(GitHub Actions Variable)이 주인이고, 이관 8단계에서 소멸한다.
interface ExtractionRoutingPolicy {
    // 이 링크의 host 에 지정된 정책. 정책 행이 없으면 null — 기본 추출 체인(구조화 → LLM, 차단 시 헤드리스 에스컬레이트).
    fun routeOf(link: ProductLink): ExtractionRoute?

    // 등록 입력 경계 전용 — UNSUPPORTED 플랫폼이면 등록을 거른다(400). parse(형식 불변식)와 분리된 정책 계약이라
    // 등록 경계가 진다: 이미 저장된 미지원 URL 조회·redirect 추적은 막지 않고, 차단이 풀리면 백오피스에서 행만 지운다.
    fun verifyRegistrable(link: ProductLink) {
        if (routeOf(link) == ExtractionRoute.UNSUPPORTED) throw ProductLinkException.unsupportedPlatform()
    }
}

// DB(extraction_platform_policies) 기반 구현. 백오피스가 배포 없이 정책을 바꾼다(알림의
// DbNotificationTemplateProvider 와 같은 패턴): 판정은 잦으므로(등록·파싱마다) 매번 DB 를 치지 않고 메모리 캐시로
// 읽고, 백오피스 수정이 reload() 로 캐시를 갱신한다. 매칭 규칙(서브도메인 포함 도메인 단위, 정규형)은
// ProductLink.matchesAnyDomain 단일 술어가 진다.
@Component
class DbExtractionRoutingPolicy(
    private val policyRepository: ExtractionPlatformPolicyJpaRepository,
) : ExtractionRoutingPolicy {
    // 불변 Map 을 통째로 교체(@Volatile)한다 — reader(등록·파싱 스레드)는 항상 옛/새 전체 중 하나만 본다
    // (DbNotificationTemplateProvider 와 같은 이유: 2단계 clear+put 이면 그 사이 빈 캐시를 읽는다).
    @Volatile
    private var domainsByRoute: Map<ExtractionRoute, List<String>> = emptyMap()

    @PostConstruct
    fun load() {
        domainsByRoute =
            policyRepository
                .findAll()
                .groupBy({ it.route }, { it.domain })
    }

    // 백오피스 수정 후 호출 — 캐시를 DB 최신으로 다시 채운다.
    fun reload() = load()

    // domain 이 PK 라 도메인당 정책이 하나이므로 첫 매치가 곧 유일한 정책이다.
    override fun routeOf(link: ProductLink): ExtractionRoute? =
        ExtractionRoute.entries.firstOrNull { route ->
            link.matchesAnyDomain(domainsByRoute[route] ?: emptyList())
        }
}
