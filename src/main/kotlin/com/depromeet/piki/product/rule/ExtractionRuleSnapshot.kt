package com.depromeet.piki.product.rule

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.JacksonException
import tools.jackson.databind.node.ObjectNode

// 지금 DB 로 만든 배포본 문자열. 모양의 정본은 TeamPiKi/infra 의 contracts/extraction.proto(ExtractionRuleSet)다.
//
// 값은 DB 만으로 정해진다(같은 DB 면 어느 인스턴스가 만들어도 같은 문자열). 그래서 대조가 문자열 비교 하나로 끝난다.
@Component
class ExtractionRuleSnapshot(
    private val ruleRepository: ExtractionRuleJpaRepository,
    private val revisionRepository: ExtractionRuleRevisionJpaRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 판 번호와 규칙 목록을 한 트랜잭션에서 읽는다 — 따로 읽으면 그 사이의 커밋이 끼어 번호와 내용이 다른 판의 것이 된다.
    //
    // 본문을 계약 클래스로 다시 찍지 않고 JSON 그대로 싣는다. 이 바이너리가 모르는 필드(신버전이 저장한 규칙)를
    // 구버전이 올리면서 지우지 않게 하려는 것이고, 해석은 읽는 쪽(extractor)이 한다.
    @Transactional(readOnly = true)
    fun build(): String {
        val root = ExtractionRuleJson.MAPPER.createObjectNode()
        root.put("version", revisionRepository.lastRevisionId())
        val rules = root.putArray("rules")
        ruleRepository.findAllByEnabledTrueOrderByIdAsc().forEach { entity ->
            val rule = readObject(entity) ?: return@forEach
            rule.put("id", entity.getId())
            rules.add(rule)
        }
        return ExtractionRuleJson.MAPPER.writeValueAsString(root)
    }

    // 저장 경계가 정규형만 넣으므로 깨진 행은 손으로 넣은 것이다. 그 행만 빼고 나머지는 올린다
    private fun readObject(entity: ExtractionRuleEntity): ObjectNode? {
        val parsed =
            try {
                ExtractionRuleJson.MAPPER.readTree(entity.ruleJson)
            } catch (e: JacksonException) {
                null
            }
        if (parsed is ObjectNode) return parsed
        log.warn("extraction rule skipped (rule_json 이 JSON 객체가 아님) id={}", entity.getId())
        return null
    }
}
