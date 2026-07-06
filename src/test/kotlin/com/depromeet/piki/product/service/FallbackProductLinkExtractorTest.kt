package com.depromeet.piki.product.service

import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.http.PageFetchException
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Fallback(진입점)이 "plain 먼저, 막히면 headless" 를 flag·escalatable 규칙대로 엮는지 Spring 없이 검증한다.
// 통합 테스트는 StubProductLinkExtractor 가 진입점(ProductLinkExtractor)을 통째 대체해 이 분기를 타지 않으므로,
// seam 의 라우팅 로직은 여기 단위에서 망라한다. 두 전략은 실제 빈(정적 fetch·헤드리스)이 네트워크/브라우저를
// 요구해 단위로 세울 수 없어, LinkExtractionStrategy 를 손으로 구현한 fake 로 "전략의 결과" 만 주입한다 —
// Fallback 의 라우팅이 검증 대상이지 전략 내부가 아니다.
class FallbackProductLinkExtractorTest {
    private val link = ProductLink.parse("https://shop.example.com/p")
    private val snapshot = ProductSnapshot(name = "나이키", currentPrice = 99_000)

    private class FakeStrategy(private val fn: (ProductLink) -> ProductSnapshot) : LinkExtractionStrategy {
        var calls = 0

        override fun extract(link: ProductLink): ProductSnapshot {
            calls++
            return fn(link)
        }
    }

    private fun fallback(
        headlessEnabled: Boolean,
        plain: FakeStrategy,
        headless: FakeStrategy,
        meterRegistry: MeterRegistry = SimpleMeterRegistry(),
    ) = FallbackProductLinkExtractor(
        plain,
        headless,
        meterRegistry,
        HeadlessExtractionProperties(enabled = headlessEnabled),
    )

    @Test
    fun `headless 가 꺼져 있으면 plain 결과를 그대로 반환하고 headless 는 호출하지 않는다`() {
        val plain = FakeStrategy { snapshot }
        val headless = FakeStrategy { error("headless 는 호출되면 안 됨") }

        val result = fallback(headlessEnabled = false, plain, headless).extract(link)

        assertEquals(snapshot, result)
        assertEquals(1, plain.calls)
        assertEquals(0, headless.calls)
    }

    @Test
    fun `headless 가 꺼져 있으면 escalatable 차단이어도 plain 예외를 그대로 전파한다`() {
        // behavior-neutral: 플래그가 꺼진 동안엔 차단(escalatable)이라도 에스컬레이트하지 않고 현재 동작(예외 전파)을 유지한다.
        val plain = FakeStrategy { throw PageFetchException.clientError(RuntimeException("403")) }
        val headless = FakeStrategy { snapshot }

        assertFailsWith<PageFetchException> {
            fallback(headlessEnabled = false, plain, headless).extract(link)
        }
        assertEquals(0, headless.calls)
    }

    @Test
    fun `headless 가 켜져 있고 plain 이 escalatable 차단으로 막히면 headless 로 에스컬레이트하고 success 로 집계한다`() {
        val plain = FakeStrategy { throw PageFetchException.clientError(RuntimeException("403")) }
        val headlessSnapshot = ProductSnapshot(name = "헤드리스 결과", currentPrice = 50_000)
        val headless = FakeStrategy { headlessSnapshot }
        val registry = SimpleMeterRegistry()

        val result = fallback(headlessEnabled = true, plain, headless, registry).extract(link)

        assertEquals(headlessSnapshot, result)
        assertEquals(1, plain.calls)
        assertEquals(1, headless.calls)
        // clientError(4xx) → category=INVALID_INPUT. outcome·category 로 escalate 를 쪼개 낭비를 조사 가능하게 한다.
        assertEquals(
            1.0,
            registry.counter("product.extract.escalation", "outcome", "success", "category", "INVALID_INPUT").count(),
        )
    }

    @Test
    fun `headless 가 켜져 있어도 plain 이 성공하면 headless 는 호출하지 않는다`() {
        // 정상 흐름(가장 흔함): headless on 이어도 plain 이 성공하면 그 결과를 그대로 쓰고 headless 를 안 부른다.
        val plain = FakeStrategy { snapshot }
        val headless = FakeStrategy { error("headless 는 호출되면 안 됨") }

        val result = fallback(headlessEnabled = true, plain, headless).extract(link)

        assertEquals(snapshot, result)
        assertEquals(1, plain.calls)
        assertEquals(0, headless.calls)
    }

    @Test
    fun `headless 가 Error 로 실패해도 failed 로 집계하고 전파한다`() {
        // headless placeholder 가 TODO()(NotImplementedError=Error) 로 바뀌어도, Exception 이 아닌 Error 라 catch 가 좁아지면
        // outcome=failed 집계가 조용히 빠질 수 있다. Throwable 을 잡아 집계하고 그대로 전파함을 고정한다.
        val plain = FakeStrategy { throw PageFetchException.clientError(RuntimeException("403")) }
        val headless = FakeStrategy { throw NotImplementedError("headless 미구현") }
        val registry = SimpleMeterRegistry()

        assertFailsWith<NotImplementedError> {
            fallback(headlessEnabled = true, plain, headless, registry).extract(link)
        }
        assertEquals(
            1.0,
            registry.counter("product.extract.escalation", "outcome", "failed", "category", "INVALID_INPUT").count(),
        )
    }

    @Test
    fun `headless 도 실패하면 그 예외를 전파하고 escalation 을 failed 로 집계한다`() {
        // 호출부(AsyncItemParsingWorker)의 재시도 판정과 맞물리는 지점이라, headless 예외가 그대로 상위로 전파돼야 한다.
        // "폴백했는데도 못 가져온" 이 outcome=failed 로 집계돼, 무조건-폴백의 낭비·한계를 관측할 수 있어야 한다.
        val plain = FakeStrategy { throw PageFetchException.clientError(RuntimeException("403")) }
        val headless = FakeStrategy { throw RuntimeException("headless 도 실패") }
        val registry = SimpleMeterRegistry()

        assertFailsWith<RuntimeException> {
            fallback(headlessEnabled = true, plain, headless, registry).extract(link)
        }
        assertEquals(1, plain.calls)
        assertEquals(1, headless.calls)
        assertEquals(
            1.0,
            registry.counter("product.extract.escalation", "outcome", "failed", "category", "INVALID_INPUT").count(),
        )
    }

    @Test
    fun `headless 가 켜져 있어도 escalatable 이 아닌 실패는 전파하고 headless 를 호출하지 않는다`() {
        // SSRF 로 우리가 막은 내부망(blockedHost)은 escalatable=false — 절대 헤드리스로 넘기지 않는다("무조건 폴백" 의 유일 예외).
        val plain = FakeStrategy { throw PageFetchException.blockedHost() }
        val headless = FakeStrategy { snapshot }

        assertFailsWith<PageFetchException> {
            fallback(headlessEnabled = true, plain, headless).extract(link)
        }
        assertEquals(0, headless.calls)
    }
}
