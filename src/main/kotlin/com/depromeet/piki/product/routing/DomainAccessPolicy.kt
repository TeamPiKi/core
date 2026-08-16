package com.depromeet.piki.product.routing

import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.domain.ProductLinkException
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

// 도메인별 접근 정책의 단일 결정 지점. "이 도메인을 어떻게 대하나"의 판정이 전부 여기로 모인다 —
// 등록 거절(BLOCKED)과 허락(ALLOWED) 둘 뿐이고, 정책 행이 없는 도메인은 기본 흐름을 그대로 탄다.
//
// 인터페이스/구현 분리는 알림의 NotificationTemplateProvider 와 같은 구조 — 소비자(등록 경계·원격 클라이언트)의
// 단위 테스트가 DB 없이 정책을 대체할 수 있게 한다.
interface DomainAccessPolicy {
    // 이 링크의 host 에 지정된 접근 정책. 행이 없으면 null — 등록을 받고, 기본 수단만 쓰는 흐름을 탄다.
    fun accessOf(link: ProductLink): DomainAccess?

    // 이 host 에 적극적인 수단까지 써도 되는가 — 플랫폼에서 받은 명시적 허락의 판정. 기본은 거부다:
    // 정책 행이 없으면(= 대부분의 도메인) false 다. 인터페이스 기본 구현을 false 로 두는 이유는 그 default-deny 를
    // 시그니처에 박기 위해서다 — 원장을 아는 구현만 true 를 낼 수 있고, 모르는 대체 구현은 거부로 수렴한다.
    fun authorizedFor(link: ProductLink): Boolean = false

    // 이 host 에 요청을 보내도 되는가. BLOCKED 면 등록 경계에서 400 으로 거르고, 파싱 경로에서도 요청 자체를
    // 내보내지 않는다 — 차단당했다고 판단한 곳에 매번 다시 두드리지 않는다는 계약이다.
    fun blocked(link: ProductLink): Boolean = accessOf(link) == DomainAccess.BLOCKED

    // 등록 입력 경계 전용 — 차단 도메인이면 등록을 거른다(400). parse(형식 불변식)와 분리된 정책 계약이라
    // 등록 경계가 진다: 차단이 풀리면 백오피스에서 행만 지운다.
    fun verifyRegistrable(link: ProductLink) {
        if (blocked(link)) throw ProductLinkException.unsupportedPlatform()
    }
}

// DB(domain_access_policies) 기반 구현. 백오피스가 배포 없이 정책을 바꾼다(알림의 DbNotificationTemplateProvider
// 와 같은 패턴): 판정은 잦으므로(등록·파싱마다) 매번 DB 를 치지 않고 메모리 캐시로 읽고, 백오피스 수정
// (afterCommit)과 주기 재적재가 reload() 로 캐시를 갱신한다. 매칭 규칙(서브도메인 포함 도메인 단위, 정규형)은
// ProductLink.matchesAnyDomain 단일 술어가 진다.
@Component
class DbDomainAccessPolicy(
    private val policyRepository: DomainAccessPolicyJpaRepository,
) : DomainAccessPolicy {
    private val log = LoggerFactory.getLogger(javaClass)

    // 불변 List 를 통째로 교체(@Volatile)한다 — reader(등록·파싱 스레드)는 항상 옛/새 전체 중 하나만 본다
    // (DbNotificationTemplateProvider 와 같은 이유: 2단계 clear+put 이면 그 사이 빈 캐시를 읽는다).
    // 도메인 길이 내림차순 정렬 — 부모/서브도메인 정책이 겹치는 host 는 더 구체적인(긴) 도메인의 정책이 이긴다.
    @Volatile
    private var policies: List<Matched> = emptyList()

    @PostConstruct
    fun load() {
        policies =
            policyRepository
                .findAll()
                .mapNotNull { toMatched(it) }
                .sortedByDescending { it.domain.length }
    }

    // tolerant reader — 이 바이너리가 모르는 access 문자열의 행은 스킵하고(그 도메인은 기본 흐름) warn 만 남긴다.
    // 엔티티를 @Enumerated 로 두면 모르는 값 한 행이 findAll 하이드레이션을 깨 @PostConstruct 부팅이 죽는다 —
    // 값을 늘린 신버전에서 행을 만든 뒤 구버전으로 롤백하면(DB 는 forward-only) 롤백 자체가 차단되는 함정.
    private fun toMatched(entity: DomainAccessPolicyEntity): Matched? {
        val access = DomainAccess.entries.find { it.name == entity.access }
        access ?: run {
            log.warn("모르는 접근 정책을 스킵(해당 도메인은 기본 흐름): domain={} access={}", entity.domain, entity.access)
            return null
        }
        return Matched(entity.domain, access)
    }

    // 백오피스 수정 직후(afterCommit)와 주기 재적재가 함께 부른다. 주기 재적재는 다른 인스턴스에서 바뀐 정책을
    // 이 인스턴스가 따라잡는 유일한 경로다 — afterCommit reload 는 수정 요청을 받은 인스턴스의 캐시만 갱신하므로,
    // blue-green 공존·수평 확장에서 stale 이 이 주기로 바운드된다. 재적재 실패(일시 DB 오류)는 예외 전파로 로그에
    // 남고 기존 캐시가 유지되며 다음 주기에 재시도된다.
    @Scheduled(fixedDelay = RELOAD_INTERVAL_MS)
    fun reload() = load()

    override fun accessOf(link: ProductLink): DomainAccess? = matched(link)?.access

    override fun authorizedFor(link: ProductLink): Boolean = accessOf(link) == DomainAccess.ALLOWED

    // 길이 내림차순 목록의 첫 매치 = 가장 구체적인 정책. domain 이 PK 라 같은 도메인 문자열의 중복은 없고,
    // 길이가 같은 서로 다른 도메인을 한 host 가 동시에 suffix 로 가질 수 없어 동률도 없다.
    private fun matched(link: ProductLink): Matched? = policies.firstOrNull { link.matchesAnyDomain(it.domains) }

    private class Matched(
        val domain: String,
        val access: DomainAccess,
    ) {
        // matchesAnyDomain 이 Collection 을 받으므로 미리 감싸 판정마다 리스트를 재생성하지 않는다.
        val domains: List<String> = listOf(domain)
    }

    companion object {
        // stale 상한(위 reload 주석). 정책 변경은 사람 손의 백오피스 조작이라 분 단위 전파면 충분하다.
        private const val RELOAD_INTERVAL_MS = 300_000L
    }
}
