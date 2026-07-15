package com.depromeet.piki.common.openapi

import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.user.domain.UserErrorCode
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// 등록된 모든 ErrorCode 의 단일 집계점. 도메인이 code 를 이관하면 자기 enum 의 entries 를 여기 한 줄 더한다.
// (config/openapi 층이라 도메인 enum 을 참조해도 된다 — 문서 조립은 합성 지점의 관심사다.)
// 유니크·형식·번호안정은 ErrorCodeCatalogTest 가 이 목록을 기계 검증한다.
object ErrorCodeRegistry {
    val all: List<ErrorCode> =
        buildList {
            addAll(UserErrorCode.entries)
            // 도메인 이관 시 여기에 addAll(XxxErrorCode.entries) 추가
        }
}

// 전체 code 사전(겹2). Swagger 상단 info.description 에 한 번만 주입되는 중앙 레퍼런스라 엔드포인트별 노이즈가 없다.
// prefix(=예외 클래스)별로 그룹핑하고 code·HTTP·의미 3열로 최소화한다. code 를 추가하면 표에 자동 등장한다(수기 아님).
fun errorCodeCatalogMarkdown(codes: List<ErrorCode>): String {
    val sections =
        codes
            .sortedBy { it.code }
            .groupBy { it.code.substringBefore("-") }
            .entries
            .joinToString("\n") { (prefix, group) ->
                val rows =
                    group.joinToString("\n") { "| ${it.code} | ${it.category.httpStatus.value()} | ${it.message} |" }
                "\n### $prefix\n\n| code | HTTP | 의미 |\n|---|---|---|\n$rows"
            }
    return "## 에러 코드\n\n실패 응답의 `code` 값 사전입니다. 성공 응답의 `code` 는 null 입니다.\n$sections"
}

@Configuration
class ErrorCodeCatalogConfig {
    // 문서 빌드 후 info.description 끝에 코드 카탈로그를 붙인다(기존 서술 뒤에 append).
    @Bean
    fun errorCodeCatalog(): OpenApiCustomizer =
        OpenApiCustomizer { openApi ->
            val info = openApi.info ?: return@OpenApiCustomizer
            info.description = (info.description ?: "") + "\n\n" + errorCodeCatalogMarkdown(ErrorCodeRegistry.all)
        }
}
