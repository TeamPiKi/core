package com.depromeet.piki.product.routing

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

// 플랫폼(host)별 추출 라우팅 정책 행. domain(정규형: 소문자·trailing dot 없음)이 자연키(PK)라 같은 도메인 문자열의
// 정책은 정확히 하나다. 백오피스가 배포 없이 추가·교체·삭제하고(NotificationTemplateEntity 와 같은 동적 설정 패턴),
// reason 은 운영 메모(왜 이 정책인가 — 실측 근거)다.
@Entity
@Table(name = "extraction_platform_policies")
class ExtractionPlatformPolicyEntity(
    @Id
    @Column(name = "domain", length = 255)
    val domain: String,
    // ExtractionRoute 의 이름 문자열. @Enumerated 로 두지 않는 이유: 구버전 바이너리가 모르는 route 행을
    // 하이드레이션조차 못 해 부팅(@PostConstruct findAll)이 죽는다 — enum 변환은 읽는 쪽
    // (DbExtractionRoutingPolicy)이 tolerant 하게 진다(모르는 route 는 스킵).
    @Column(name = "route", nullable = false, length = 32)
    val route: String,
    @Column(name = "reason", length = 255)
    val reason: String?,
) {
    init {
        // 생성 경로(admin 서비스·테스트)의 불변식 — JPA no-arg 하이드레이션은 init 을 타지 않으므로 DB 의 기존 행을
        // 막지는 못한다. 정규화 자체는 경계(AdminExtractionPolicyService.normalize)가 책임지고, 여기는 새 생성
        // 경로가 정규화를 빠뜨리는 코드 버그를 잡는 층이다.
        require(domain.isNotBlank()) { "domain 이 비어 있습니다." }
        require(domain == domain.trim().trimEnd('.').lowercase()) { "domain 은 정규형(소문자·trailing dot 없음)이어야 합니다." }
    }

    // 행 수정은 upsert(같은 PK 로 새 인스턴스 save = 교체)라 새 인스턴스의 생성 시각이 곧 마지막 변경 시각이 된다.
    @Column(name = "updated_at", nullable = false)
    val updatedAt: LocalDateTime = LocalDateTime.now()
}
