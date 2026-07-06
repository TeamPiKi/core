package com.depromeet.piki.product.routing

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

// 플랫폼(host)별 추출 라우팅 정책 행. domain(정규형: 소문자·trailing dot 없음)이 자연키(PK)라 도메인당 정책은
// 정확히 하나다 — UNSUPPORTED 이면서 HEADLESS_FIRST 인 모순 상태가 스키마로 차단된다. 백오피스가 배포 없이
// 추가·삭제하고(NotificationTemplateEntity 와 같은 동적 설정 패턴), reason 은 운영 메모(왜 이 정책인가 — 실측 근거)다.
@Entity
@Table(name = "extraction_platform_policies")
class ExtractionPlatformPolicyEntity(
    @Id
    @Column(name = "domain", length = 255)
    val domain: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "route", nullable = false, length = 32)
    val route: ExtractionRoute,
    @Column(name = "reason", length = 255)
    val reason: String?,
) {
    init {
        require(domain.isNotBlank()) { "domain 이 비어 있습니다." }
        // 정규형 불변식 — 매칭(matchesAnyDomain)은 정규형 목록을 전제한다. 경계(admin 서비스)가 정규화를 책임지고,
        // 여기 닿은 비정규형은 경계 누락 버그다.
        require(domain == domain.trim().trimEnd('.').lowercase()) { "domain 은 정규형(소문자·trailing dot 없음)이어야 합니다." }
    }

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
        protected set
}
