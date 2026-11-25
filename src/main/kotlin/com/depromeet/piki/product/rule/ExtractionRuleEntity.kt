package com.depromeet.piki.product.rule

import com.depromeet.piki.common.domain.LongBaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

// 몰별 추출 규칙의 원본. extractor 가 읽는 배포본은 ExtractionRulePublisher 가 이 행들로 만들어 Redis 에 올린다.
@Entity
@Table(name = "extraction_rules")
class ExtractionRuleEntity(
    label: String,
    ruleJson: String,
    sampleUrl: String,
    enabled: Boolean,
) : LongBaseEntity() {
    @Column(nullable = false, length = LABEL_MAX_LENGTH)
    var label: String = label
        protected set

    // 계약의 ExtractionRule JSON 에서 id 만 뺀 것. id 는 이 행의 PK 가 배포 때 채운다
    @Column(nullable = false, columnDefinition = "TEXT")
    var ruleJson: String = ruleJson
        protected set

    @Column(nullable = false, length = SAMPLE_URL_MAX_LENGTH)
    var sampleUrl: String = sampleUrl
        protected set

    @Column(nullable = false)
    var enabled: Boolean = enabled
        protected set

    init {
        validate(label, ruleJson, sampleUrl)
    }

    fun edit(
        label: String,
        ruleJson: String,
        sampleUrl: String,
        enabled: Boolean,
    ) {
        validate(label, ruleJson, sampleUrl)
        this.label = label
        this.ruleJson = ruleJson
        this.sampleUrl = sampleUrl
        this.enabled = enabled
    }

    private fun validate(
        label: String,
        ruleJson: String,
        sampleUrl: String,
    ) {
        require(label.isNotBlank() && label.length <= LABEL_MAX_LENGTH) { "label 길이가 범위를 벗어났습니다." }
        require(ruleJson.isNotBlank() && ruleJson.length <= RULE_JSON_MAX_LENGTH) { "ruleJson 길이가 범위를 벗어났습니다." }
        require(sampleUrl.isNotBlank() && sampleUrl.length <= SAMPLE_URL_MAX_LENGTH) { "sampleUrl 길이가 범위를 벗어났습니다." }
    }

    companion object {
        const val LABEL_MAX_LENGTH = 100
        const val SAMPLE_URL_MAX_LENGTH = 2048

        // TEXT 컬럼이라 DB 가 막지 않는다. 사람이 쓰는 규칙 하나는 이 길이를 넘지 않는다
        const val RULE_JSON_MAX_LENGTH = 8_000
    }
}
