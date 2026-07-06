package com.depromeet.piki.product.service.remote

import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.FallbackProductLinkExtractor
import com.depromeet.piki.product.service.HeadlessExtractionProperties
import com.depromeet.piki.product.service.LinkExtractionStrategy
import com.depromeet.piki.product.service.ProductSnapshot
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals

// 라우팅(원격 vs embedded)의 host 게이트 분기를 Spring 없이 망라한다. enabled 스위치는 이 클래스가 아니라
// @ConditionalOnProperty 로 빈 자체가 뜨지 않는 컨텍스트 레벨 동작이다 — 기본 통합 테스트 컨텍스트
// (remote 미설정)가 기존 StubProductLinkExtractor 구조 그대로 도는 것이 그 증명이므로 여기선 다루지 않는다.
class RoutingProductLinkExtractorTest {
    // 원격 결과의 단일 출처 — FakeRemote 와 단언이 같은 인스턴스를 본다(리터럴 이중화 방지).
    private val remoteSnapshot = ProductSnapshot(name = "원격 결과", currentPrice = 1_000)
    private val embeddedSnapshot = ProductSnapshot(name = "임베디드 결과", currentPrice = 2_000)

    // HttpProductLinkExtractor 는 @Component 라 all-open 으로 열려 있어 fake 서브클래스로 결과만 주입한다
    // (실 구현은 네트워크를 요구해 단위로 세울 수 없다 — FallbackProductLinkExtractorTest 의 FakeStrategy 와 같은 접근).
    private inner class FakeRemote : HttpProductLinkExtractor(RestClient.builder().build()) {
        var calls = 0

        override fun extract(link: ProductLink): ProductSnapshot {
            calls++
            return remoteSnapshot
        }
    }

    private inner class RecordingPlain : LinkExtractionStrategy {
        var calls = 0

        override fun extract(link: ProductLink): ProductSnapshot {
            calls++
            return embeddedSnapshot
        }
    }

    private fun routing(
        hosts: List<String>,
        remote: FakeRemote,
        plain: RecordingPlain,
    ): RoutingProductLinkExtractor {
        // embedded 는 실제 진입점 빈(Fallback) 그대로 조립한다. headless 는 꺼져 있어(behavior-neutral) 도달 불가 —
        // 도달하면 즉시 깨지도록 throw 전략을 둔다(셋업 원칙: 의도된 시나리오인 양 동작 가능한 값을 두지 않는다).
        val headless =
            object : LinkExtractionStrategy {
                override fun extract(link: ProductLink): ProductSnapshot =
                    error("headless 는 이 테스트에서 호출되면 안 된다 (enabled=false)")
            }
        val embedded =
            FallbackProductLinkExtractor(
                plain,
                headless,
                SimpleMeterRegistry(),
                HeadlessExtractionProperties(enabled = false),
            )
        return RoutingProductLinkExtractor(
            remote,
            embedded,
            RemoteExtractionProperties(baseUrl = "http://extractor.test", hosts = hosts),
        )
    }

    @ParameterizedTest(name = "hosts={0}, url={1} → 원격={2}")
    @CsvSource(
        // 빈 목록 = 아무것도 원격으로 가지 않는다 (안전 기본값 — enabled 만 켜도 컷오버가 안 터진다)
        "'', https://www.musinsa.com/products/1, false",
        // 전량 전환은 '*' 명시로만
        "'*', https://any.example.com/p, true",
        // 도메인 단위 매칭 — 서브도메인 포함
        "'musinsa.com', https://www.musinsa.com/products/1, true",
        // 목록 밖 host 는 embedded
        "'musinsa.com', https://www.29cm.co.kr/products/1, false",
        // 부분 문자열이 아니라 도메인 단위 — notmusinsa.com 은 musinsa.com 의 서브도메인이 아니다
        "'musinsa.com', https://notmusinsa.com/products/1, false",
        // 설정 입력의 대소문자·trailing dot 은 정규화된다 (env 입력 편차가 매칭을 무산시키지 않는다)
        "'Musinsa.com.', https://www.musinsa.com/products/1, true",
        // 여러 host 중 하나 매칭
        "'29cm.co.kr,musinsa.com', https://www.29cm.co.kr/p/1, true",
    )
    fun `host 게이트가 원격과 embedded 를 가른다`(
        hostsCsv: String,
        url: String,
        expectRemote: Boolean,
    ) {
        val hosts = hostsCsv.split(",").filter { it.isNotBlank() }
        val remote = FakeRemote()
        val plain = RecordingPlain()

        val result = routing(hosts, remote, plain).extract(ProductLink.parse(url))

        if (expectRemote) {
            assertEquals(remoteSnapshot, result)
            assertEquals(1, remote.calls)
            assertEquals(0, plain.calls)
        } else {
            assertEquals(embeddedSnapshot, result)
            assertEquals(0, remote.calls)
            assertEquals(1, plain.calls)
        }
    }

    @Test
    fun `원격 결과는 변형 없이 그대로 전파된다`() {
        val remote = FakeRemote()
        val plain = RecordingPlain()

        val result = routing(listOf("*"), remote, plain).extract(ProductLink.parse("https://shop.example.com/p"))

        assertEquals(remoteSnapshot, result)
    }
}
