package com.depromeet.piki.common.config

import com.depromeet.piki.support.IntegrationTestSupport
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.micrometer.tracing.Tracer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// 비동기 executor 가 부모 요청의 trace context 를 워커 스레드로 전파하는지 검증한다.
// ContextPropagatingTaskDecorator 가 없으면 trace context(ThreadLocal)가 @Async 경계에서 끊겨
// 워커 로그에 traceId 가 빈 채로 찍혀, 한 요청의 전체 로그를 traceId 로 추적할 수 없다 (회귀 가드).
//
// 특정 executor 를 하드코딩하지 않고 컨텍스트의 모든 ThreadPoolTaskExecutor 빈을 순회한다 —
// AsyncConfig 에 executor 가 새로 추가돼도 별도 테스트 추가 없이 자동으로 trace 전파가 검증된다.
// 별도 스레드 동작 검증이라 @Transactional 자동 롤백 패턴을 쓰지 않는다 (DB 는 건드리지 않는다).
class AsyncTracePropagationIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var tracer: Tracer

    @Autowired
    private lateinit var observationRegistry: ObservationRegistry

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @TestFactory
    fun `모든 async executor 가 워커 스레드로 부모 요청의 trace context 를 전파한다`(): List<DynamicTest> {
        val executors = applicationContext.getBeansOfType(ThreadPoolTaskExecutor::class.java)
        // 자동 순회가 AsyncConfig 의 executor 를 실제로 커버하는지 가드 — 빈 맵이거나 누락되면 테스트가 무의미해진다.
        assertTrue(
            AsyncConfig.EVENT_PUBLICATION_EXECUTOR in executors && AsyncConfig.NOTIFICATION_EXECUTOR in executors,
            "AsyncConfig 의 executor 빈이 순회 대상에 포함돼야 한다 (현재: ${executors.keys})",
        )
        return executors.map { (name, executor) ->
            DynamicTest.dynamicTest("$name 은 trace context 를 워커 스레드로 전파한다") {
                assertTracePropagated(executor)
            }
        }
    }

    private fun assertTracePropagated(executor: Executor) {
        val workerTraceId = CompletableFuture<String?>()
        val workerMdc = CompletableFuture<String?>()

        // 실제 HTTP 요청처럼 Observation 으로 trace scope 를 연다 — brave span 과 MDC(traceId)가 함께 채워진다.
        val observation = Observation.start("test.parent", observationRegistry)
        val scope = observation.openScope()
        val parentTraceId: String?
        try {
            parentTraceId = tracer.currentSpan()?.context()?.traceId()
            // execute 호출 시점(부모 scope 안)에 TaskDecorator 가 ContextSnapshot 을 캡처한다.
            executor.execute {
                workerTraceId.complete(tracer.currentSpan()?.context()?.traceId())
                workerMdc.complete(MDC.get("traceId"))
            }
        } finally {
            scope.close()
            observation.stop()
        }

        assertNotNull(parentTraceId, "부모 스레드에 trace context 가 있어야 한다 (없으면 Tracer autoconfig 회귀)")
        assertEquals(
            parentTraceId,
            workerTraceId.get(3, TimeUnit.SECONDS),
            "워커 스레드의 span traceId 가 부모와 일치해야 한다",
        )
        assertEquals(
            parentTraceId,
            workerMdc.get(3, TimeUnit.SECONDS),
            "워커 스레드의 MDC traceId 가 부모와 일치해야 한다 (로그 correlation 의 본질)",
        )
    }
}
