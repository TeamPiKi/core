package com.depromeet.piki.common.config

import io.lettuce.core.tracing.LettuceObservationContext
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import net.ttddyy.observation.tracing.ConnectionContext
import net.ttddyy.observation.tracing.QueryContext
import org.springframework.http.server.observation.ServerRequestObservationContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.scheduling.support.ScheduledTaskObservationContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ObservationPredicate 분기 망라 — observation 단계에서 무엇을 제외하고 무엇을 남기는지.
// (어떤 observation 이 span·메트릭을 만들지 가르는 순수 로직이라 Spring 컨텍스트 없이 검증한다.)
class ObservationConfigTest {
    private val predicate = ObservationConfig().filterNoiseObservations()

    private fun serverRequest(uri: String): ServerRequestObservationContext =
        ServerRequestObservationContext(MockHttpServletRequest("GET", uri), MockHttpServletResponse())

    // 핸들러가 하나도 없으면 SimpleObservationRegistry.isNoop() 이 true 가 되어 createNotStarted 가
    // context 생성·parent 세팅을 통째로 건너뛴다(fast-path). 운영 레지스트리는 핸들러(메트릭·트레이싱)가
    // 항상 있으므로, 그 경로를 재현하려면 테스트 레지스트리에도 핸들러를 달아야 한다.
    private fun observingRegistry(): ObservationRegistry {
        val registry = ObservationRegistry.create()
        registry.observationConfig().observationHandler(ObservationHandler<Observation.Context> { true })
        return registry
    }

    @Test
    fun `@Scheduled 폴링 observation 은 제외된다`() {
        val context = ScheduledTaskObservationContext(Runnable {}, Runnable::class.java.getMethod("run"))
        assertFalse(predicate.test("tasks.scheduled.execution", context))
    }

    @Test
    fun `actuator 요청 observation 은 제외된다`() {
        assertFalse(predicate.test("http.server.requests", serverRequest("/actuator/prometheus")))
    }

    @Test
    fun `실제 API 요청 observation 은 유지된다`() {
        assertTrue(predicate.test("http.server.requests", serverRequest("/api/v1/wishes")))
    }

    @Test
    fun `actuator 가 아닌 일반 observation(item_parse 등)은 유지된다`() {
        assertTrue(predicate.test("item.parse", Observation.Context()))
    }

    @Test
    fun `부모 없는 JDBC 관측(스케줄러 폴링의 커넥션·쿼리)은 제외된다`() {
        // @Scheduled 억제로 폴링 틱의 JDBC 관측은 parent 가 없는 루트가 된다 — "connection" 트레이스 도배의 원인(#840).
        assertFalse(predicate.test("jdbc.connection", ConnectionContext()))
        assertFalse(predicate.test("jdbc.query", QueryContext()))
    }

    @Test
    fun `작업 안(부모 있음)의 JDBC 관측은 유지된다`() {
        // HTTP 요청·item.parse 처럼 실제 작업 scope 안의 JDBC 는 그 자식 span 으로 남아야 한다.
        val context = QueryContext()
        context.parentObservation = Observation.createNotStarted("item.parse", observingRegistry())
        assertTrue(predicate.test("jdbc.query", context))
    }

    @Test
    fun `부모 없는 Redis 관측(actuator scrape 안의 allowlist 조회 등)은 제외된다`() {
        // actuator 요청 억제로 그 요청 안의 Redis 명령이 부모 없는 루트가 된다 — 30초마다 쌓이던
        // "exists"·"pexpire" 낱장 트레이스의 원인(#889).
        assertFalse(predicate.test("redis.command", LettuceObservationContext("Redis")))
    }

    @Test
    fun `작업 안(부모 있음)의 Redis 관측은 유지된다`() {
        val context = LettuceObservationContext("Redis")
        context.parentObservation = Observation.createNotStarted("http.server.requests", observingRegistry())
        assertTrue(predicate.test("redis.command", context))
    }

    @Test
    fun `부모 없는 Spring Security 필터체인 관측은 제외된다`() {
        // 같은 actuator 요청에서 필터체인 before·after 가 각각 루트가 되어 scrape 당 낱장 2건을 더 만들었다(#889).
        assertFalse(predicate.test("spring.security.http.secured.requests", Observation.Context()))
    }

    @Test
    fun `작업 안(부모 있음)의 Spring Security 필터체인 관측은 유지된다`() {
        // 정상 API 요청에서는 security span 이 그 요청 span 의 자식으로 붙는다(Tempo 실측). 그건 그대로 남겨야 한다.
        val context = Observation.Context()
        context.parentObservation = Observation.createNotStarted("http.server.requests", observingRegistry())
        assertTrue(predicate.test("spring.security.http.secured.requests", context))
    }

    @Test
    fun `Spring Security 관측 이름은 우리가 거르는 prefix 를 유지한다`() {
        // Security 의 context 타입(FilterChainObservationContext)이 package-private final 이라 JDBC·Redis 처럼
        // 타입으로 식별할 수 없고 observation name 에 의존한다. 라이브러리가 이름을 바꿨을 때 조용히 새는 대신
        // 여기서 깨지도록, 실제 상수를 읽어 prefix 를 고정한다.
        val decorator = Class.forName("org.springframework.security.web.ObservationFilterChainDecorator")
        listOf("SECURED_OBSERVATION_NAME", "UNSECURED_OBSERVATION_NAME").forEach { fieldName ->
            val value = decorator.getDeclaredField(fieldName).apply { isAccessible = true }.get(null) as String
            assertTrue(
                value.startsWith(ObservationConfig.SPRING_SECURITY_PREFIX),
                "$fieldName = $value 가 ${ObservationConfig.SPRING_SECURITY_PREFIX} 로 시작하지 않는다",
            )
        }
    }

    @Test
    fun `억제된 @Scheduled scope 안의 JDBC 관측(noop 부모)도 제외된다`() {
        // dev 실환경 재현(#851): predicate 로 억제된 @Scheduled observation 은 noop 이지만, 그 scope 가
        // micrometer 의 공유 static thread-local 에 등록되어 실제 레지스트리의 current 로 잡힌다. 그러면
        // 폴링 틱 JDBC 관측의 parent 가 null 이 아니라 noop 으로 들어와 "부모 없음" 판별을 통과했고,
        // noop 부모는 span 이 없어 결국 루트 "connection" 트레이스가 계속 생성됐다. noop 부모도 거부해야 한다.
        val registry = observingRegistry()
        registry.observationConfig().observationPredicate(predicate)
        val suppressedScheduled =
            Observation.createNotStarted(
                "tasks.scheduled.execution",
                { ScheduledTaskObservationContext(Runnable {}, Runnable::class.java.getMethod("run")) },
                registry,
            )

        suppressedScheduled.observe {
            // datasource-micrometer 와 같은 경로: createNotStarted 가 내부에서 current 를 parent 로 채운 뒤
            // predicate 를 평가한다. 거부되면 반환 observation 이 noop 이다.
            val connectionContext = ConnectionContext()
            val connectionObservation = Observation.createNotStarted("jdbc.connection", { connectionContext }, registry)

            assertTrue(
                (connectionContext.parentObservation as? Observation)?.isNoop() ?: false,
                "재현 전제: 공유 thread-local 을 타고 noop 부모가 잡혀야 한다",
            )
            assertTrue(connectionObservation.isNoop(), "noop 부모의 JDBC 관측은 거부되어야 한다")
        }
    }
}
