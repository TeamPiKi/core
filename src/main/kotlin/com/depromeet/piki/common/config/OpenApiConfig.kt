package com.depromeet.piki.common.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean
    fun openAPI(
        @Value("\${spring.application.name:piki-core}") applicationName: String,
    ): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("$applicationName API")
                    // API 버전은 빌드 버전(0.0.1-SNAPSHOT)과 별개의 public 계약 버전이므로 별도 관리
                    // project.version은 Gradle 컨텍스트 전용이라 Spring Bean에서 직접 참조 불가
                    .version("v1")
                    .description(
                        """
                        피키(PIKI) 위시리스트 서비스 API 문서.

                        - 모든 응답은 공통 래퍼(`ApiResponseBody`)로 `application/json` 으로 내려간다. 필드는 `status`·`code`·`detail`·`data`·`pageResponse` 이며, 성공·실패가 같은 형태다. 실패 시 `data` 는 null 이고 `code`·`detail` 에 사유가 담긴다.
                        - 인증은 JWT 기반이다. 게스트 생성 또는 소셜 로그인으로 액세스·리프레시 토큰 쌍을 발급받고, 보호된 API 는 액세스 토큰을 `Authorization: Bearer {accessToken}` 헤더로 전달한다. 만료 시 리프레시 토큰으로 갱신한다.
                        """.trimIndent(),
                    ).contact(Contact().name("PIKI").url("https://github.com/TeamPiKi/core"))
                    .license(License().name("Apache-2.0").url("https://www.apache.org/licenses/LICENSE-2.0")),
            )
            // JWT Bearer 인증 스킴을 스펙에 선언한다. 이게 없으면 문서 UI 가 "인증 필요" 를 알 수 없어
            // 토큰 입력란·Authorization 헤더 cURL 샘플을 그리지 못한다 (Security 필터 규칙은 springdoc 이 모름).
            .components(
                Components().addSecuritySchemes(
                    SECURITY_SCHEME_NAME,
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("게스트 생성·소셜 로그인으로 발급받은 액세스 토큰. `Bearer ` 접두어는 문서 UI 가 자동으로 붙인다."),
                ),
            )
            // 기본적으로 모든 엔드포인트에 Bearer 를 요구한다. 인증이 필요 없는 public 엔드포인트
            // (게스트 생성·토큰 갱신·health) 는 각 핸들러에서 `@SecurityRequirements` 로 이 요구를 해제한다.
            .addSecurityItem(SecurityRequirement().addList(SECURITY_SCHEME_NAME))
            .addServersItem(Server().url("/").description("Current host"))

    // 문서 UI(Stoplight Elements)는 OpenAPI spec 의 tags[] 배열 순서를 사이드바 그룹 순서로 그대로
    // 반영한다. springdoc 3.0.x 는 이 배열을 핸들러 스캔 순서(빈/핸들러 등록 순서에 의존해 사실상
    // 비결정적)로 채워, OpenAPI 빈의 .tags() 로는 순서가 잡히지 않는다(실측). 그래서 문서 빌드가 끝난
    // 뒤 OpenApiCustomizer 로 tags 를 도메인 흐름 순(TAG_ORDER)으로 재정렬한다. TAG_ORDER 에 없는
    // 태그(새로 추가되고 등록을 빠뜨린 것)는 맨 뒤로 보내되 이름 알파벳으로 정렬한다 — 누락은
    // OpenApiDocsIntegrationTest 의 순서 단언이 잡는다.
    @Bean
    fun sortTagsByDomainFlow(): OpenApiCustomizer =
        OpenApiCustomizer { openApi ->
            val tags = openApi.tags ?: return@OpenApiCustomizer
            openApi.tags =
                tags.sortedWith(
                    compareBy(
                        { TAG_ORDER.indexOf(it.name).let { idx -> if (idx < 0) Int.MAX_VALUE else idx } },
                        { it.name },
                    ),
                )
        }

    companion object {
        private const val SECURITY_SCHEME_NAME = "bearerAuth"

        // 사이드바 그룹 순서: 인증·내 정보(진입) → 핵심 도메인(위시 → 토너먼트 → 토너먼트 아이템) →
        // 알림(SSE·FCM) → 개발 전용. health-controller 는 @Tag 미선언 자동 태그라 spec 의 top-level
        // tags[] 에 없어 여기 둘 필요가 없고, Stoplight Elements 가 사이드바 맨 뒤에 자동 배치한다.
        private val TAG_ORDER =
            listOf(
                "Auth",
                "User",
                "Wishlist",
                "Tournament",
                "Tournament Item",
                "Notification",
                "Announcement",
                "Image Proxy",
                "FCM",
                "Dev",
            )
    }
}
