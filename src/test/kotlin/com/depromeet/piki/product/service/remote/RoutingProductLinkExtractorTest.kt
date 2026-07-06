package com.depromeet.piki.product.service.remote

import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.FallbackProductLinkExtractor
import com.depromeet.piki.product.service.HeadlessExtractionProperties
import com.depromeet.piki.product.service.LinkExtractionStrategy
import com.depromeet.piki.product.service.ProductSnapshot
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals

// 라우팅(원격 vs embedded)의 host 매칭 분기를 Spring 없이 망라한다. enabled=false 는 이 클래스가 아니라
// @ConditionalOnProperty 로 빈 자체가 뜨지 않는 컨텍스트 레벨 동작이다 — 기본 통합 테스트 컨텍스트
// (remote 미설정)가 기존 StubProductLinkExtractor 구조 그대로 도는 것이 그 증명이므로 여기선 다루지 않는다.
class RoutingProductLinkExtractorTest {
    private val remoteSnapshot = ProductSnapshot(name = "원격 결과", currentPrice = 1_000)
    private val embeddedSnapshot = ProductSnapshot(name = "임베디드 결과", currentPrice = 2_000)

    // HttpProductLinkExtractor 는 @Component 라 all-open 으로 열려 있어 fake 서브클래스로 결과만 주입한다
    // (실 구현은 네트워크를 요구해 단위로 세울 수 없다 — FallbackProductLinkExtractorTest 의 FakeStrategy 와 같은 접근).
    private class FakeRemote : HttpProductLinkExtractor(RestClient.builder().build()) {
        var calls = 0

        override fun extract(link: ProductLink): ProductSnapshot {
            calls++
            return ProductSnapshot(name = "원격 결과", currentPrice = 1_000)
        }
    }

    private class RecordingStrategy(private val snapshot: ProductSnapshot) : LinkExtractionStrategy {
        var calls = 0

        override fun extract(link: ProductLink): ProductSnapshot {
            calls++
            return snapshot
        }
    }

    private fun routing(
        hosts: List<String>,
        remote: FakeRemote,
        embeddedPlain: RecordingStrategy,
    ): RoutingProductLinkExtractor {
        val embedded =
            FallbackProductLinkExtractor(
                embeddedPlain,
                RecordingStrategy(embeddedSnapshot),
                SimpleMeterRegistry(),
                HeadlessExtractionProperties(enabled = false),
            )
        return RoutingProductLinkExtractor(
            remote,
            embedded,
            RemoteExtractionProperties(enabled = true, baseUrl = "http://extractor.test", hosts = hosts),
        )
    }

    @Test
    fun `hosts 가 비어 있으면 전량 원격으로 보낸다`() {
        val remote = FakeRemote()
        val plain = RecordingStrategy(embeddedSnapshot)

        val result = routing(emptyList(), remote, plain).extract(ProductLink.parse("https://any.example.com/p"))

        assertEquals(remoteSnapshot.name, result.name)
        assertEquals(1, remote.calls)
        assertEquals(0, plain.calls)
    }

    @Test
    fun `host 가 목록과 매칭되면(서브도메인 포함) 원격으로 보낸다`() {
        val remote = FakeRemote()
        val plain = RecordingStrategy(embeddedSnapshot)

        val result =
            routing(listOf("musinsa.com"), remote, plain)
                .extract(ProductLink.parse("https://www.musinsa.com/products/1"))

        assertEquals(remoteSnapshot.name, result.name)
        assertEquals(1, remote.calls)
        assertEquals(0, plain.calls)
    }

    @Test
    fun `host 가 목록과 매칭되지 않으면 embedded 경로로 보낸다`() {
        val remote = FakeRemote()
        val plain = RecordingStrategy(embeddedSnapshot)

        val result =
            routing(listOf("musinsa.com"), remote, plain)
                .extract(ProductLink.parse("https://www.29cm.co.kr/products/1"))

        assertEquals(embeddedSnapshot.name, result.name)
        assertEquals(0, remote.calls)
        assertEquals(1, plain.calls)
    }

    @Test
    fun `도메인 단위로만 매칭한다 - 무관 도메인의 부분 문자열은 embedded 로 남는다`() {
        val remote = FakeRemote()
        val plain = RecordingStrategy(embeddedSnapshot)

        // notmusinsa.com 은 musinsa.com 의 서브도메인이 아니다 — endsWith(".musinsa.com") 매칭이어야 안 걸린다.
        val result =
            routing(listOf("musinsa.com"), remote, plain)
                .extract(ProductLink.parse("https://notmusinsa.com/products/1"))

        assertEquals(embeddedSnapshot.name, result.name)
        assertEquals(0, remote.calls)
        assertEquals(1, plain.calls)
    }
}
