package com.depromeet.piki.common.config

import io.micrometer.observation.Observation
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
        context.parentObservation = Observation.createNotStarted("item.parse", ObservationRegistry.create())
        assertTrue(predicate.test("jdbc.query", context))
    }
}
