package com.depromeet.piki.common.ratelimit

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

// 아이템 등록 한도의 백오피스 오버라이드 행(#934). **서비스 전체에 최대 하나만 존재한다** — 축별로 갈리는
// extraction_models 와 달리 이 값들은 전역 설정이라 키가 없고, PK 를 상수로 못박아 두 행이 생기지 않게 한다.
//
// **모든 값이 nullable 이고 null 은 "이 노브는 env 기본값을 쓴다" 는 뜻이다.** 그래서 상한 하나만 급히 내릴 때
// 나머지를 화면에서 다시 적어 넣을 필요가 없다. 행 자체가 없으면 전부 기본값이다.
@Entity
@Table(name = "item_quota_settings")
class ItemQuotaSettingsEntity(
    @Column(name = "enabled")
    val enabled: Boolean? = null,
    @Column(name = "user_limit")
    val userLimit: Int? = null,
    @Column(name = "capacity_limit")
    val capacityLimit: Int? = null,
    @Column(name = "capacity_alert_percent")
    val capacityAlertPercent: Int? = null,
) {
    // 행이 하나뿐이라 PK 가 식별이 아니라 "단일 행" 이라는 제약 그 자체다. save 가 늘 같은 id 를 쓰므로
    // 저장은 자동으로 upsert 가 된다 — "지우고 새로 넣기" 로 수정하면 그 사이 전부 기본값으로 돌아가는 창이 생긴다.
    @Id
    @Column(name = "id")
    val id: Byte = SINGLE_ROW_ID

    init {
        // 불변식 층 — 값의 사용자 대면 검증은 입력 경계(AdminItemQuotaService)가 지고, 여기는 새 생성 경로가
        // 그것을 빠뜨리는 코드 버그를 잡는다(CLAUDE.md "검증은 입력 경계와 엔티티 양쪽에 둔다").
        // null 은 "기본값 사용" 이라 검증 대상이 아니다 — 값이 있을 때만 범위를 본다.
        userLimit?.let { require(it > 0) { "user_limit($it)은 양수여야 한다 — 0 이면 등록이 통째로 막힌다." } }
        capacityLimit?.let { require(it > 0) { "capacity_limit($it)은 양수여야 한다 — 0 이면 모든 사용자가 막힌다." } }
        capacityAlertPercent?.let { require(it in 1..100) { "capacity_alert_percent($it)는 1 에서 100 사이여야 한다." } }
    }

    @Column(name = "updated_at", nullable = false)
    val updatedAt: LocalDateTime = LocalDateTime.now()

    companion object {
        // DDL 의 CHECK (id = 1) 과 같은 값. 코드가 다른 id 를 쓰면 DB 제약이 막는다(이중 방어).
        const val SINGLE_ROW_ID: Byte = 1
    }
}

interface ItemQuotaSettingsJpaRepository : JpaRepository<ItemQuotaSettingsEntity, Byte>
