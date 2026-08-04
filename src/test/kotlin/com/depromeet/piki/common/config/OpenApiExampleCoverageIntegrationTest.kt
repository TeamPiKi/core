package com.depromeet.piki.common.config

import com.depromeet.piki.support.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertTrue

// CLAUDE.md "응답 전수 문서화 — 절대 규칙" 중 기계로 오탐 없이 판정 가능한 절반을 강제한다.
//
// 그 규칙은 두 가지를 요구한다.
//   (a) 도달 가능한 응답이 전부 @ApiResponse 로 선언돼 있을 것
//   (b) 선언된 모든 응답에 대응하는 example 이 붙어 있을 것 ("한쪽만 있으면 위반이다")
//
// (a) 는 "서비스·도메인이 이 엔드포인트에서 어떤 예외를 던질 수 있나" 라는 정적 도달성 분석이라 호출 그래프를
// 따라가야 하고, 입력 경계가 먼저 거르는 경우(OAuth invalidRequest 등)를 오탐 없이 가려낼 수 없다 — 사람 판단이
// 끼므로 산문에 남긴다. (b) 는 렌더된 spec 만 보면 결정되므로 여기서 강제한다.
//
// 실패하면 두 방법 중 하나로 고친다: example 을 추가하거나(대개 이쪽), 그 응답이 실제로 도달 불가라면
// @ApiResponse 선언 자체를 지운다. "선언은 해두고 example 은 생략" 은 docs 가 payload 모양을 숨기는 것이라 허용하지 않는다.
class OpenApiExampleCoverageIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private fun mockMvc() =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    @Test
    fun `api-docs 에 선언된 모든 응답에는 example payload 가 붙어 있다`() {
        val spec =
            objectMapper.readTree(
                mockMvc()
                    .perform(get("/v3/api-docs"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response
                    .contentAsString,
            )

        val violations = spec.get("paths").collectViolations()

        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine("example 이 없는 응답 ${violations.size} 건:")
                violations.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("각 응답에 대응하는 example 을 *ApiExamples 에 등록하거나,")
                appendLine("도달 불가한 응답이면 *Api.kt 의 @ApiResponse 선언을 제거하라.")
            },
        )
    }

    // paths 를 훑어 "JSON body 를 내리는데 example 이 없는" 응답을 모은다.
    // body 가 없는 응답(3xx 리다이렉트의 ResponseEntity<Void> 등)과 JSON 이 아닌 응답(SSE·이미지 바이트)은
    // 애초에 보여줄 payload 가 없으므로 대상이 아니다.
    private fun JsonNode.collectViolations(): List<String> =
        properties().flatMap { (path, pathItem) ->
            pathItem.properties().flatMap { (method, operation) ->
                val responses = operation["responses"] ?: return@flatMap emptyList()
                responses.properties().mapNotNull { (code, response) ->
                    val jsonContent = response["content"]?.get(APPLICATION_JSON) ?: return@mapNotNull null
                    // examples 노드가 아예 없거나(부착 안 됨), 있어도 실제 payload(value)를 가진 named example 이
                    // 하나도 없으면(예: `{ "placeholder": {} }`) 위반이다.
                    val examples = jsonContent["examples"]
                    val hasValuedExample =
                        examples?.properties()?.any { (_, example) -> example["value"]?.let { true } ?: false }
                            ?: false
                    val missingExample = hasValuedExample.not()
                    "${method.uppercase()} $path → $code".takeIf { missingExample }
                }
            }
        }

    companion object {
        private const val APPLICATION_JSON = "application/json"
    }
}
