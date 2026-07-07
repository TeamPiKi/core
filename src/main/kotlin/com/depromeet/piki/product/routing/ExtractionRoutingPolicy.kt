package com.depromeet.piki.product.routing

import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.domain.ProductLinkException
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
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
// 읽고, 백오피스 수정(afterCommit)과 주기 재적재가 reload() 로 캐시를 갱신한다. 매칭 규칙(서브도메인 포함
// 도메인 단위, 정규형)은 ProductLink.matchesAnyDomain 단일 술어가 진다.
@Component
class DbExtractionRoutingPolicy(
    private val policyRepository: ExtractionPlatformPolicyJpaRepository,
) : ExtractionRoutingPolicy {
    private val log = LoggerFactory.getLogger(javaClass)

    // 불변 List 를 통째로 교체(@Volatile)한다 — reader(등록·파싱 스레드)는 항상 옛/새 전체 중 하나만 본다
    // (DbNotificationTemplateProvider 와 같은 이유: 2단계 clear+put 이면 그 사이 빈 캐시를 읽는다).
    // 도메인 길이 내림차순 정렬 — 부모/서브도메인 정책이 겹치는 host(예: a-bly.com 차단 + m.a-bly.com 직행)는
    // 더 구체적인(긴) 도메인의 정책이 이긴다. enum 선언 순서 같은 암묵 규칙에 기대지 않는다.
    @Volatile
    private var policies: List<DomainPolicy> = emptyList()

    @PostConstruct
    fun load() {
        policies =
            policyRepository
                .findAll()
                .mapNotNull { toDomainPolicy(it) }
                .sortedByDescending { it.domain.length }
    }

    // tolerant reader — 이 바이너리가 모르는 route 문자열의 행은 스킵하고(기본 체인으로 판정) warn 만 남긴다.
    // 엔티티를 @Enumerated 로 두면 모르는 값 한 행이 findAll 하이드레이션을 깨 @PostConstruct 부팅이 죽는다 —
    // route 를 늘린 신버전에서 행을 만든 뒤 구버전으로 롤백하면(DB 는 forward-only) 롤백 자체가 차단되는 함정.
    private fun toDomainPolicy(entity: ExtractionPlatformPolicyEntity): DomainPolicy? {
        val route = ExtractionRoute.entries.find { it.name == entity.route }
        route ?: run {
            log.warn("모르는 추출 route 를 스킵(해당 도메인은 기본 체인): domain={} route={}", entity.domain, entity.route)
            return null
        }
        return DomainPolicy(entity.domain, route)
    }

    // 백오피스 수정 직후(afterCommit)와 주기 재적재가 함께 부른다. 주기 재적재는 다른 인스턴스에서 바뀐 정책을
    // 이 인스턴스가 따라잡는 유일한 경로다 — afterCommit reload 는 수정 요청을 받은 인스턴스의 캐시만 갱신하므로,
    // blue-green 공존·수평 확장에서 stale 이 이 주기로 바운드된다. 재적재 실패(일시 DB 오류)는 예외 전파로 로그에
    // 남고 기존 캐시가 유지되며 다음 주기에 재시도된다.
    @Scheduled(fixedDelay = RELOAD_INTERVAL_MS)
    fun reload() = load()

    // 길이 내림차순 목록의 첫 매치 = 가장 구체적인 정책. domain 이 PK 라 같은 도메인 문자열의 중복은 없고,
    // 길이가 같은 서로 다른 도메인을 한 host 가 동시에 suffix 로 가질 수 없어 동률도 없다.
    override fun routeOf(link: ProductLink): ExtractionRoute? = policies.firstOrNull { link.matchesAnyDomain(it.domains) }?.route

    private class DomainPolicy(
        val domain: String,
        val route: ExtractionRoute,
    ) {
        // matchesAnyDomain 이 Collection 을 받으므로 미리 감싸 판정마다 리스트를 재생성하지 않는다.
        val domains: List<String> = listOf(domain)
    }

    companion object {
        // stale 상한(위 reload 주석). 정책 변경은 사람 손의 백오피스 조작이라 분 단위 전파면 충분하다.
        private const val RELOAD_INTERVAL_MS = 300_000L
    }
}
