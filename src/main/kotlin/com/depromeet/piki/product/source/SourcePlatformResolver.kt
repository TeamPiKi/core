package com.depromeet.piki.product.source

import com.depromeet.piki.product.domain.ProductLink
import jakarta.annotation.PostConstruct
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

// 출처 커머스몰 표시명(sourcePlatform)의 단일 결정 지점 (#766). 응답 시점에 URL 에서 유도한다 — items 에 저장하지
// 않으므로 백오피스 수정·신규 등록이 과거 item 에도 즉시 소급된다.
// 인터페이스/구현 분리는 ExtractionRoutingPolicy 와 같은 구조 — 소비자의 단위 테스트가 DB 없이 대체할 수 있게 한다.
interface SourcePlatformResolver {
    // 이 링크의 출처 몰 표시명. 백오피스 등록값(도메인 최장 일치)이 우선하고, 없으면 host 에서 유도한 임시값
    // (SourcePlatformFallback). 링크가 없거나(이미지 등록 item) host 가 없으면 null — 유도할 재료 자체가 없다.
    fun resolve(link: ProductLink?): String?
}

// DB(source_platforms) 기반 구현. 백오피스가 배포 없이 표시명을 바꾼다(DbExtractionRoutingPolicy 와 같은 패턴):
// 판정은 잦으므로(위시 목록 항목마다) 매번 DB 를 치지 않고 메모리 캐시로 읽고, 백오피스 수정(afterCommit)과
// 주기 재적재가 reload() 로 캐시를 갱신한다. 매칭 규칙(서브도메인 포함 도메인 단위, 정규형)은
// ProductLink.matchesAnyDomain 단일 술어가 진다.
@Component
class DbSourcePlatformResolver(
    private val sourcePlatformRepository: SourcePlatformJpaRepository,
) : SourcePlatformResolver {
    // 불변 List 를 통째로 교체(@Volatile)한다 — reader(응답 조립 스레드)는 항상 옛/새 전체 중 하나만 본다.
    // 도메인 길이 내림차순 정렬 — 부모/서브도메인 등록이 겹치는 host 는 더 구체적인(긴) 도메인의 표시명이 이긴다.
    @Volatile
    private var platforms: List<DomainDisplayName> = emptyList()

    @PostConstruct
    fun load() {
        platforms =
            sourcePlatformRepository
                .findAll()
                .map { DomainDisplayName(it.domain, it.displayName) }
                .sortedByDescending { it.domain.length }
    }

    // 백오피스 수정 직후(afterCommit)와 주기 재적재가 함께 부른다. 주기 재적재는 다른 인스턴스에서 바뀐 등록을
    // 이 인스턴스가 따라잡는 유일한 경로다 (DbExtractionRoutingPolicy.reload 와 같은 이유·같은 stale 상한).
    @Scheduled(fixedDelay = RELOAD_INTERVAL_MS)
    fun reload() = load()

    override fun resolve(link: ProductLink?): String? {
        link ?: return null
        val host = link.normalizedHost() ?: return null
        return platforms.firstOrNull { link.matchesAnyDomain(it.domains) }?.displayName
            ?: SourcePlatformFallback.of(host)
    }

    private class DomainDisplayName(
        val domain: String,
        val displayName: String,
    ) {
        // matchesAnyDomain 이 Collection 을 받으므로 미리 감싸 판정마다 리스트를 재생성하지 않는다.
        val domains: List<String> = listOf(domain)
    }

    companion object {
        // stale 상한. 표시명 변경은 사람 손의 백오피스 조작이라 분 단위 전파면 충분하다.
        private const val RELOAD_INTERVAL_MS = 300_000L
    }
}
