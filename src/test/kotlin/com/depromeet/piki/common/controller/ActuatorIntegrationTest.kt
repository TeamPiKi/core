package com.depromeet.piki.common.controller

import com.depromeet.piki.support.IntegrationTestSupport
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsProperties
import org.springframework.boot.micrometer.metrics.autoconfigure.PropertiesMeterFilter
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.filter.ServerHttpObservationFilter
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

class ActuatorIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var metricsProperties: MetricsProperties

    @Autowired
    private lateinit var observationRegistry: ObservationRegistry

    companion object {
        // OTel 표준 explicit bucket 경계 14개 (5ms 부터 10s) — application.yml distribution.slo 와 짝
        private val OTEL_DEFAULT_BOUNDARIES = listOf(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0)
    }

    @Test
    fun `GET actuator health - 인증 없이 200, status UP 이 와야 한다 (Alloy scrape 가드)`() {
        // EC2 내부 Grafana Alloy 가 인증 없이 localhost 로 scrape 해야 한다.
        // SecurityConfig 의 permitAll 이 누락되면 401 이 되어 수집이 끊긴다.
        // actuator health 응답은 ApiResponseBody 래퍼가 아닌 actuator 고유 포맷이라
        // $.status 는 우리 API 의 숫자 코드가 아니라 문자열 "UP" 이다.
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()

        mockMvc
            .perform(get("/actuator/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
    }

    @Test
    fun `GET actuator prometheus - 인증 없이 200, JVM 메트릭 텍스트가 노출돼야 한다`() {
        // micrometer-registry-prometheus 가 클래스패스에 있고 prometheus 엔드포인트가
        // exposure.include 에 포함돼야 텍스트 포맷 메트릭이 노출된다.
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()

        mockMvc
            .perform(get("/actuator/prometheus"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("jvm_")))
    }

    @Test
    fun `GET actuator loggers - 인증 없이 200, 런타임 로그 레벨 변경 엔드포인트가 노출돼야 한다`() {
        // 평소 DEBUG 인 비-API 인증 로그를 조사 시 런타임에 켜려면 loggers 가 exposure.include + permitAll 이어야 한다.
        // (외부 도달은 nginx 가 /actuator/ 403 으로 차단하므로, permitAll 은 localhost 한정 도달을 전제로 한다.)
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()

        mockMvc
            .perform(get("/actuator/loggers"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("DEBUG")))
    }

    @Test
    fun `http server requests 타이머에 OTel 표준 SLO 버킷이 붙는다 (Grafana 지연 패널 데이터 가드)`() {
        // 대시보드 지연 패널(RED p50/p95/p99 · 한눈 p99 stat)은 http_server_requests_seconds_bucket 을 읽는다.
        // application.yml 의 distribution.slo 가 빠지면 bucket 시계열이 노출되지 않아 그 패널 전부가 조용히
        // No data 로 죽으므로(#839), yml 바인딩(MetricsProperties)을 실제 적용 필터(PropertiesMeterFilter)에
        // 태워 검증한다. 공유 컨텍스트의 PrometheusMeterRegistry 에 프로브 미터를 직접 등록하면 실제
        // http.server.requests 와 라벨 키가 달라 시계열이 드롭될 수 있어(#465), 같은 필터를 로컬 레지스트리에
        // 적용해 격리 검증한다.
        val registry = SimpleMeterRegistry()
        registry.config().meterFilter(PropertiesMeterFilter(metricsProperties))
        val timer = Timer.builder("http.server.requests").register(registry)

        val buckets =
            timer
                .takeSnapshot()
                .histogramCounts()
                .map { it.bucket(TimeUnit.SECONDS) }
                .filter { it.isFinite() } // +Inf 버킷은 SLO 설정과 무관하게 붙으므로 제외

        assertEquals(OTEL_DEFAULT_BOUNDARIES, buckets)
    }

    @Test
    fun `실제 요청을 기록한 뒤 actuator prometheus 가 le 경계 전부를 노출한다 (exposition 경로 가드)`() {
        // 위 바인딩 가드는 로컬 SimpleMeterRegistry 격리라 실제 MVC 계측(ServerHttpObservationFilter)과
        // PrometheusMeterRegistry 의 텍스트 노출 경로를 안 탄다 — 그 경로가 끊겨도(예: 관측 필터·핸들러 배선
        // 회귀) 초록인 사각이 남는다. 그래서 실제 요청 한 건을 관측 필터로 기록하고, Alloy 가 긁는 바로 그
        // 텍스트에 bucket family 와 14개 le 경계가 전부 나오는지 검증한다. 요청 대상은 /health —
        // permitAll 이면서 /actuator 밖이라 ObservationConfig 의 actuator 제외에도 안 걸린다.
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .addFilters<DefaultMockMvcBuilder>(ServerHttpObservationFilter(observationRegistry))
                .build()

        mockMvc.perform(get("/health")).andExpect(status().isOk)

        val metricsText =
            mockMvc
                .perform(get("/actuator/prometheus"))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString

        // le 값은 렌더링 표기(1.0 vs 1 등)에 묶이지 않게 숫자로 파싱해 비교한다. +Inf 는 숫자 패턴에 안 걸려 자연 제외.
        val exposedBoundaries =
            Regex("http_server_requests_seconds_bucket\\{[^}]*le=\"([0-9.]+)\"")
                .findAll(metricsText)
                .map { it.groupValues[1].toDouble() }
                .toSortedSet()
                .toList()
        assertEquals(OTEL_DEFAULT_BOUNDARIES, exposedBoundaries)
    }

    @Test
    fun `SLO 버킷 설정은 main 과 test 의 application yml 이 같은 값으로 유지된다 (미러 드리프트 가드)`() {
        // test classpath 의 application.yml 이 main 을 통째로 대체하므로 위 바인딩 테스트는 test yml 만 본다.
        // main 쪽에서만 설정이 빠지면 테스트는 초록인 채 운영 대시보드 지연 패널이 조용히 죽는 사각이 남아,
        // 두 파일의 SLO 라인을 직접 대조한다 (소스 스캔 상대경로는 TestConventionTest 와 같은 방식).
        fun sloValue(path: String): String {
            val lines =
                File(path)
                    .readLines()
                    .filter { it.trimStart().startsWith("\"[http.server.requests]\"") }
            assertEquals(1, lines.size, "$path 에 http.server.requests SLO 설정 라인이 정확히 1개 있어야 한다")
            return lines.single().substringAfter(":").trim()
        }

        assertEquals(sloValue("src/main/resources/application.yml"), sloValue("src/test/resources/application.yml"))
    }

    @Test
    fun `GET actuator metrics - 미노출 경로라 인증 없이 접근 시 401 (노출 정책 회귀 가드)`() {
        // metrics 엔드포인트는 application.yml exposure.include 에서 제외했고 SecurityConfig
        // permitAll 에도 없다. permitAll 이 아니므로 anyRequest().authenticated() 에 걸려 인증 없이는 401.
        //
        // 주의 — 이 401 은 "permitAll 아님"만 증명한다. Security 필터가 DispatcherServlet 보다 먼저라
        // 엔드포인트 등록 여부와 무관하게 401 이 난다. 따라서 누군가 metrics 를 exposure 에 노출하되
        // permitAll 을 빠뜨리면 여전히 401 이라 이 가드는 못 잡는다. 이 가드가 실제로 막는 회귀는
        // "노출 + permitAll 까지 돼 외부에 열리는" 경우다 (그 조합이면 200 이 되어 단언이 깨진다).
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()

        mockMvc
            .perform(get("/actuator/metrics"))
            .andExpect(status().isUnauthorized)
    }
}
