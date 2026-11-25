package com.depromeet.piki.product.rule

import com.depromeet.piki.contracts.extraction.v1.ExtractionRule
import com.depromeet.piki.contracts.extraction.v1.FieldSource
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.util.JsonFormat
import tools.jackson.databind.json.JsonMapper

// 규칙 본문의 입력 경계. 사람이 쓴 JSON 을 계약 모양으로 읽어 보고, 저장할 정규형을 만든다.
//
// extractor 는 해석하지 못하는 규칙을 그 규칙만 조용히 버린다(TeamPiKi/infra contracts/extraction-api.md 7절).
// 그래서 여기서 통과시킨 규칙이 저쪽에서 버려지면 "저장했는데 적용이 안 됨" 이 된다. 문서만으로 판정되는 모양은
// 여기서 거절하고, 선택자 문법처럼 문서를 읽어 봐야 아는 것은 저장 전 시험 추출이 본다.
internal object ExtractionRuleJson {
    // 모르는 필드를 무시하지 않는다 — 필드 이름의 오타가 "출처를 적지 않음" 으로 조용히 읽히면 안 된다.
    private val parser = JsonFormat.parser()
    private val printer = JsonFormat.printer().omittingInsignificantWhitespace()

    // 배포본 문자열이 이 매퍼의 출력으로 정해진다. 전역 ObjectMapper 빈을 쓰면 그 설정이 바뀔 때 같은 DB 에서 다른
    // 문자열이 나와, blue-green 두 인스턴스가 대조 주기마다 서로 덮어쓴다.
    val MAPPER: JsonMapper = JsonMapper.builder().build()

    private val HOST = Regex("[a-z0-9_-]+(\\.[a-z0-9_-]+)+")
    private const val EXPRESSION_MAX_LENGTH = 500

    // 정규형은 id 를 뺀다. id 는 행의 PK 가 원장이라 본문에 적힌 값과 어긋날 수 있다.
    fun canonical(rawJson: String): String {
        val rule = parse(rawJson)
        validateTarget(rule)
        require(rule.fetch != ExtractionRule.FetchMode.UNRECOGNIZED) { "fetch 값을 읽지 못했습니다." }
        require(rule.absentOriginalPrice != ExtractionRule.AbsentOriginalPrice.UNRECOGNIZED) {
            "absentOriginalPrice 값을 읽지 못했습니다."
        }
        if (rule.hasName()) validateSource(rule.name, "name")
        if (rule.hasImageUrl()) validateSource(rule.imageUrl, "imageUrl")
        if (rule.hasCurrentPrice()) validateSource(rule.currentPrice, "currentPrice")
        if (rule.hasOriginalPrice()) validateSource(rule.originalPrice, "originalPrice")
        // 계약 프린터는 = 와 ' 를 =·' 로 찍는다. 편집 화면에 그대로 보이므로 읽히는 모양으로 다시 쓴다
        val canonical = MAPPER.writeValueAsString(MAPPER.readTree(printer.print(rule.toBuilder().clearId())))
        require(canonical.length <= ExtractionRuleEntity.RULE_JSON_MAX_LENGTH) {
            "규칙이 너무 깁니다 (${ExtractionRuleEntity.RULE_JSON_MAX_LENGTH}자 이내)."
        }
        return canonical
    }

    fun parse(rawJson: String): ExtractionRule {
        val builder = ExtractionRule.newBuilder()
        try {
            parser.merge(rawJson, builder)
        } catch (e: InvalidProtocolBufferException) {
            throw IllegalArgumentException("규칙 JSON 을 읽지 못했습니다: ${e.message}")
        }
        return builder.build()
    }

    private fun validateTarget(rule: ExtractionRule) {
        when (rule.targetCase) {
            ExtractionRule.TargetCase.HOST ->
                require(HOST.matches(rule.host)) { "host 는 소문자 도메인만 적어 주세요 (스킴·경로 제외, 예: oliveyoung.co.kr)" }
            ExtractionRule.TargetCase.MARKER -> validateExpression(rule.marker, "marker 의 선택자")
            ExtractionRule.TargetCase.TARGET_NOT_SET ->
                throw IllegalArgumentException("대상(host 또는 marker)을 적어 주세요.")
        }
    }

    private fun validateSource(
        source: FieldSource,
        field: String,
    ) {
        when (source.sourceCase) {
            FieldSource.SourceCase.CSS -> validateExpression(source.css.selector, "$field 의 선택자")
            FieldSource.SourceCase.JSON -> {
                validateExpression(source.json.script, "$field 의 script 선택자")
                validateExpression(source.json.pointer, "$field 의 pointer")
                require(source.json.pointer.startsWith("/")) { "$field 의 pointer 는 / 로 시작해야 합니다." }
            }
            FieldSource.SourceCase.SOURCE_NOT_SET ->
                throw IllegalArgumentException("$field 의 출처(css 또는 json)를 적어 주세요.")
        }
    }

    private fun validateExpression(
        value: String,
        name: String,
    ) {
        require(value.isNotBlank()) { "$name 이(가) 비었습니다." }
        require(value.length <= EXPRESSION_MAX_LENGTH) { "$name 은(는) ${EXPRESSION_MAX_LENGTH}자를 초과할 수 없습니다." }
    }
}
