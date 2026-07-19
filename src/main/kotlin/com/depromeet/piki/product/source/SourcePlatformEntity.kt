package com.depromeet.piki.product.source

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

// 출처 커머스몰 표시명 행 (#766). domain(정규형: 소문자·trailing dot 없음)이 자연키(PK)라 같은 도메인 문자열의
// 표시명은 정확히 하나다. 백오피스가 배포 없이 추가·교체·삭제하고(ExtractionPlatformPolicyEntity 와 같은 동적 설정
// 패턴), display_name 은 클라이언트 응답(sourcePlatform)에 그대로 나가는 사용자 대면 표기다.
@Entity
@Table(name = "source_platforms")
class SourcePlatformEntity(
    @Id
    @Column(name = "domain", length = 255)
    val domain: String,
    @Column(name = "display_name", nullable = false, length = 255)
    val displayName: String,
) {
    init {
        // 생성 경로(admin 서비스·테스트)의 불변식 — JPA no-arg 하이드레이션은 init 을 타지 않으므로 DB 의 기존 행을
        // 막지는 못한다. 정규화 자체는 경계(AdminSourcePlatformService.normalize)가 책임지고, 여기는 새 생성
        // 경로가 정규화를 빠뜨리는 코드 버그를 잡는 층이다.
        require(domain.isNotBlank()) { "domain 이 비어 있습니다." }
        require(domain == domain.trim().trimEnd('.').lowercase()) { "domain 은 정규형(소문자·trailing dot 없음)이어야 합니다." }
        require(displayName.isNotBlank()) { "displayName 이 비어 있습니다." }
    }

    // 행 수정은 upsert(같은 PK 로 새 인스턴스 save = 교체)라 새 인스턴스의 생성 시각이 곧 마지막 변경 시각이 된다.
    @Column(name = "updated_at", nullable = false)
    val updatedAt: LocalDateTime = LocalDateTime.now()
}
