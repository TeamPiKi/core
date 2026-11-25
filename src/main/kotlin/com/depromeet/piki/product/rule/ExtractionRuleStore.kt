package com.depromeet.piki.product.rule

import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class SaveExtractionRuleCommand(
    val label: String,
    val ruleJson: String,
    val sampleUrl: String,
    val enabled: Boolean,
    val actor: String,
)

// 규칙 원본의 쓰기 경계. DB 에 쓰고(ExtractionRuleWriter), 그 트랜잭션이 끝난 뒤 배포본을 다시 올린다.
//
// 올리기를 afterCommit 에 걸지 않는 이유: 그 시점은 DB 커넥션을 아직 쥐고 있어 Redis 호출이 커넥션을 붙든다.
// 그래서 이 클래스에는 트랜잭션을 두지 않는다 — 바깥 트랜잭션 안에서 부르면 커밋 전의 내용이 올라간다.
//
// 입력 오류는 백오피스 화면이 그대로 보여 주는 IllegalArgumentException 으로 던진다(AdminExtractionPolicyService 와 같은 방식).
@Service
class ExtractionRuleStore(
    private val writer: ExtractionRuleWriter,
    private val publisher: ExtractionRulePublisher,
) {
    fun create(command: SaveExtractionRuleCommand): Long {
        val id = writer.create(normalize(command), command.actor)
        publisher.publishQuietly()
        return id
    }

    fun update(
        id: Long,
        command: SaveExtractionRuleCommand,
    ) {
        writer.update(id, normalize(command), command.actor)
        publisher.publishQuietly()
    }

    fun delete(
        id: Long,
        actor: String,
    ) {
        writer.delete(id, actor)
        publisher.publishQuietly()
    }

    private fun normalize(command: SaveExtractionRuleCommand): NormalizedExtractionRule {
        val label = command.label.trim()
        val sampleUrl = command.sampleUrl.trim()
        require(label.isNotEmpty()) { "이름을 입력해 주세요." }
        require(label.length <= ExtractionRuleEntity.LABEL_MAX_LENGTH) {
            "이름은 ${ExtractionRuleEntity.LABEL_MAX_LENGTH}자를 초과할 수 없습니다."
        }
        require(sampleUrl.isNotEmpty()) { "시험에 쓸 상품 주소를 입력해 주세요." }
        require(sampleUrl.length <= ExtractionRuleEntity.SAMPLE_URL_MAX_LENGTH) {
            "상품 주소는 ${ExtractionRuleEntity.SAMPLE_URL_MAX_LENGTH}자를 초과할 수 없습니다."
        }
        return NormalizedExtractionRule(label, ExtractionRuleJson.canonical(command.ruleJson), sampleUrl, command.enabled)
    }
}

class NormalizedExtractionRule(
    val label: String,
    val ruleJson: String,
    val sampleUrl: String,
    val enabled: Boolean,
)

// 저장·삭제와 그 이력 한 줄을 한 트랜잭션으로 묶는다. 같은 빈 안의 호출은 프록시를 거치지 않아 별도 빈이다.
@Component
class ExtractionRuleWriter(
    private val ruleRepository: ExtractionRuleJpaRepository,
    private val revisionRepository: ExtractionRuleRevisionJpaRepository,
) {
    @Transactional
    fun create(
        input: NormalizedExtractionRule,
        actor: String,
    ): Long {
        val rule = ruleRepository.save(ExtractionRuleEntity(input.label, input.ruleJson, input.sampleUrl, input.enabled))
        revisionRepository.save(ExtractionRuleRevisionEntity.of(rule, ExtractionRuleChange.SAVED, actor))
        return rule.getId()
    }

    @Transactional
    fun update(
        id: Long,
        input: NormalizedExtractionRule,
        actor: String,
    ) {
        val rule = locked(id)
        rule.edit(input.label, input.ruleJson, input.sampleUrl, input.enabled)
        revisionRepository.save(ExtractionRuleRevisionEntity.of(rule, ExtractionRuleChange.SAVED, actor))
    }

    @Transactional
    fun delete(
        id: Long,
        actor: String,
    ) {
        val rule = locked(id)
        revisionRepository.save(ExtractionRuleRevisionEntity.of(rule, ExtractionRuleChange.DELETED, actor))
        ruleRepository.delete(rule)
    }

    private fun locked(id: Long): ExtractionRuleEntity =
        ruleRepository.findForUpdate(id) ?: throw IllegalArgumentException("없는 규칙입니다: $id")
}
