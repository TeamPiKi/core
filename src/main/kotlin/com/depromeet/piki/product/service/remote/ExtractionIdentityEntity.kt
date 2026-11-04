package com.depromeet.piki.product.service.remote

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

// 대상 몰에 밝히는 신원의 백오피스 지정 행. 서비스 전체에 최대 하나이고 행이 없으면 extractor·renderer 의 기본 신원이다.
@Entity
@Table(name = "extraction_identity_settings")
class ExtractionIdentityEntity(
    @Column(nullable = false, length = ExtractionIdentityRule.MAX_LENGTH)
    val identity: String,
) {
    // PK 가 "단일 행" 제약 그 자체라 save 가 늘 같은 행을 덮어쓴다(ItemQuotaSettingsEntity 와 같은 구조).
    @Id
    val id: Byte = SINGLE_ROW_ID

    @Column(nullable = false)
    val updatedAt: LocalDateTime = LocalDateTime.now()

    init {
        // 불변식 층 — 사용자 대면 문구는 입력 경계(AdminExtractionIdentityService)가 내보낸다.
        ExtractionIdentityRule.violationOf(identity)?.let { throw IllegalArgumentException("identity($identity) 가 입력 규칙을 어긴다: $it") }
    }

    companion object {
        // DDL 의 CHECK (id = 1) 과 같은 값.
        const val SINGLE_ROW_ID: Byte = 1
    }
}

interface ExtractionIdentityJpaRepository : JpaRepository<ExtractionIdentityEntity, Byte>
