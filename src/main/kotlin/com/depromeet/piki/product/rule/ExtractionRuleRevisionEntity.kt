package com.depromeet.piki.product.rule

import com.depromeet.piki.common.domain.LongBaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

// 규칙을 저장·삭제할 때마다 그 시점의 본문을 한 줄씩 쌓는다. 고치지 않는다.
// 마지막 id 가 배포본의 판 번호라, 규칙을 지워도 번호가 뒤로 가지 않는다.
@Entity
@Table(name = "extraction_rule_revisions")
class ExtractionRuleRevisionEntity(
    @Column(nullable = false, updatable = false)
    val extractionRuleId: Long,

    // ExtractionRuleChange 의 이름. @Enumerated 로 두지 않는 이유는 DomainAccessPolicyEntity.access 와 같다
    @Column(nullable = false, updatable = false, length = 16)
    val changeType: String,

    @Column(nullable = false, updatable = false, length = ExtractionRuleEntity.LABEL_MAX_LENGTH)
    val label: String,

    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    val ruleJson: String,

    @Column(nullable = false, updatable = false, length = ExtractionRuleEntity.SAMPLE_URL_MAX_LENGTH)
    val sampleUrl: String,

    @Column(nullable = false, updatable = false)
    val enabled: Boolean,

    @Column(nullable = false, updatable = false, length = ACTOR_MAX_LENGTH)
    val actor: String,
) : LongBaseEntity() {
    companion object {
        const val ACTOR_MAX_LENGTH = 50

        fun of(
            rule: ExtractionRuleEntity,
            change: ExtractionRuleChange,
            actor: String,
        ): ExtractionRuleRevisionEntity =
            ExtractionRuleRevisionEntity(
                extractionRuleId = rule.getId(),
                changeType = change.name,
                label = rule.label,
                ruleJson = rule.ruleJson,
                sampleUrl = rule.sampleUrl,
                enabled = rule.enabled,
                actor = actor.take(ACTOR_MAX_LENGTH),
            )
    }
}

enum class ExtractionRuleChange(
    val description: String,
) {
    SAVED("만들거나 고침"),
    DELETED("지움. 본문은 지우기 직전의 것"),
}
