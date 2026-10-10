package com.depromeet.piki.item.domain

import com.depromeet.piki.product.domain.ProductLink
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// 목록에 이름을 올리려면 그 이름이 붙은 실제 URL 과, 지워도 같은 상품이 열린다는 확인이 먼저다
class NormalizedInputTest {
    private fun normalized(raw: String): NormalizedInput = requireNotNull(NormalizedInput.of(ParseInput.Link(ProductLink.parse(raw))))

    // --- 실제로 관측한 URL ---

    @Test
    fun `무신사 onelink 귀결점의 공유자 추적 쿼리가 전부 떨어져 맨 URL 과 같은 정체성이 된다`() {
        val dirty =
            normalized(
                "https://www.musinsa.com/products/6760200?source_caller=sdk&is_retargeting=true" +
                    "&shortlink=hxnauj24&af_channel=mobile_share&af_referrer_uid=1588832660726-6009526" +
                    "&pid=af_app_invites&af_referrer_customer_id=jsy0714",
            )
        val bare = normalized("https://www.musinsa.com/products/6760200")
        assertEquals(bare, dirty)
    }

    // 2026-10-04 단축링크를 직접 따라가 받은 귀결점
    @Test
    fun `무신사 단축링크가 실제로 붙여 온 파라미터 12개가 전부 떨어진다`() {
        val redirected =
            normalized(
                "https://www.musinsa.com/products/6760200?af_force_deeplink=true&af_referrer_customer_id=jsy0714" +
                    "&utm_source=kakao&onelink_id=PvkC&af_dp=musinsaad%3A%2F%2Fopen&af_channel=mobile_share&shortlink=hxnauj24" +
                    "&af_referrer_uid=1588832660726-6009526&af_siteid=1003139529&pid=af_app_invites&source_caller=sdk&is_retargeting=true",
            )
        assertEquals(normalized("https://www.musinsa.com/products/6760200"), redirected)
    }

    @Test
    fun `29cm 의 reward_key 가 떨어진다 - 공유자마다 다른 토큰이 정체성을 가르지 못한다`() {
        val fromShareA = normalized("https://www.29cm.co.kr/products/3915971?reward_key=RK_AAAA&utm_source=29cm_pdp_share")
        val fromShareB = normalized("https://www.29cm.co.kr/products/3915971?reward_key=RK_BBBB")
        assertEquals(fromShareA, fromShareB)
    }

    @Test
    fun `29cm 카테고리 탐색 쿼리가 떨어져 브라우저 복사 URL 과 같은 정체성이 된다`() {
        val browsed =
            normalized(
                "https://www.29cm.co.kr/products/3863006?categoryLargeCode=269100100&categoryMediumCode=269101100&categorySmallCode=",
            )
        assertEquals(normalized("https://www.29cm.co.kr/products/3863006"), browsed)
    }

    @Test
    fun `지그재그 단축 귀결점 경로가 웹 경로로 접혀 클릭마다 바뀌는 쿼리와 무관하게 같은 정체성이 된다`() {
        // event_uuid 는 클릭마다, referrer_timestamp 는 밀리초마다 값이 바뀐다
        val fromShortlink =
            normalized(
                "https://zigzag.kr/p/164171173?event_uuid=390edeb9-cc26-414f&referrer_timestamp=1785321370803" +
                    "&utm_source=sharelink&channel=sharelink&deeplink_url=zigzag%3A%2F%2Fopen",
            )
        val fromWeb = normalized("https://zigzag.kr/catalog/products/164171173")
        assertEquals(fromWeb, fromShortlink)
    }

    @Test
    fun `onelink 단축의 utm 이 떨어진다`() {
        val withUtm = normalized("https://musinsa.onelink.me/PvkC/hxnauj24?utm_source=kakao")
        assertEquals(normalized("https://musinsa.onelink.me/PvkC/hxnauj24"), withUtm)
    }

    // onelink 의 긴 형식은 경로가 템플릿뿐이고 목적지를 쿼리에 싣는다. 통째로 지우면 서로 다른 상품이 한 URL 로 접힌다
    @Test
    fun `onelink 의 모르는 쿼리는 남는다`() {
        val a = normalized("https://musinsa.onelink.me/PvkC?deep_link_value=6760200")
        val b = normalized("https://musinsa.onelink.me/PvkC?deep_link_value=6252844")
        assertNotEquals(a, b)
    }

    @ParameterizedTest
    @CsvSource(
        "https://kream.co.kr/products/984835?foo=bar, https://kream.co.kr/products/984835",
        "https://m.a-bly.com/goods/70768193?abc=1, https://m.a-bly.com/goods/70768193",
        "https://s.zigzag.kr/pby6JjF2dc?x=1, https://s.zigzag.kr/pby6JjF2dc",
    )
    fun `관측한 적 없는 파라미터는 어느 몰에서든 남는다`(
        withUnknown: String,
        bare: String,
    ) {
        assertNotEquals(normalized(bare), normalized(withUnknown))
    }

    // --- 규칙 ---

    @Test
    fun `추적 파라미터 이름은 몰과 무관하게 떨어진다`() {
        val shared =
            normalized(
                "https://unknown-mall.example.com/goods/77?source_caller=sdk&is_retargeting=true&shortlink=abc" +
                    "&event_uuid=390edeb9&referrer_timestamp=1785321370803&reward_key=RK_AAAA&channel=sharelink",
            )
        assertEquals(normalized("https://unknown-mall.example.com/goods/77"), shared)
    }

    // pid 는 AppsFlyer 에서는 유입 경로이고 다른 몰에서는 상품 번호일 수 있다
    @Test
    fun `pid 는 AppsFlyer 파라미터가 같이 있을 때만 떨어진다`() {
        assertEquals(
            normalized("https://www.musinsa.com/products/6760200"),
            normalized("https://www.musinsa.com/products/6760200?pid=af_app_invites&af_channel=mobile_share"),
        )
        assertNotEquals(
            normalized("https://unknown-mall.example.com/goods?pid=123"),
            normalized("https://unknown-mall.example.com/goods?pid=456"),
        )
        assertEquals(
            "https://unknown-mall.example.com/goods?pid=123",
            normalized("https://unknown-mall.example.com/goods?pid=123&utm_source=instagram").value,
        )
    }

    @Test
    fun `cafe24 계열은 쿼리가 곧 상품 번호라 보존된다 - 전부 제거하면 그 몰의 모든 상품이 하나로 합쳐지는 사고`() {
        val a = normalized("https://m.reetkeem.com/product/detail.html?product_no=9275&cate_no=65")
        val b = normalized("https://m.reetkeem.com/product/detail.html?product_no=9090&cate_no=65")
        assertNotEquals(a, b)
    }

    @Test
    fun `미확인 몰도 추적 파라미터는 이름으로 떨어진다 - 광고에서 복사한 URL 의 fbclid 와 utm`() {
        val fromAd =
            normalized(
                "https://cosymosy.co.kr/shop_view/?idx=4360&utm_source=instagram&utm_medium=paid&fbclid=PAdGRleASVDk1",
            )
        assertEquals(normalized("https://cosymosy.co.kr/shop_view/?idx=4360"), fromAd)
    }

    @Test
    fun `남는 쿼리는 이름순 정렬되어 파라미터 순서만 다른 URL 이 같은 정체성이 된다`() {
        val a = normalized("https://m.reetkeem.com/product/detail.html?product_no=9275&cate_no=65")
        val b = normalized("https://m.reetkeem.com/product/detail.html?cate_no=65&product_no=9275")
        assertEquals(a, b)
    }

    // --- 전역 규칙 ---

    @Test
    fun `fragment 는 떨어진다 - HTTP 요청에 실리지 않아 서버 렌더 몰에서 상품을 바꿀 수 없다`() {
        val withFragment = normalized("https://m.reetkeem.com/product/loose-unbal-tee/9277/category/65/display/1/#none")
        val bare = normalized("https://m.reetkeem.com/product/loose-unbal-tee/9277/category/65/display/1/")
        assertEquals(bare, withFragment)
    }

    @Test
    fun `trailing slash 유무로 정체성이 갈리지 않는다`() {
        assertEquals(
            normalized("https://shopsoez.com/product/boat-neck/72"),
            normalized("https://shopsoez.com/product/boat-neck/72/"),
        )
    }

    @Test
    fun `host 대소문자와 trailing dot 이 정규화된다`() {
        assertEquals(
            normalized("https://www.musinsa.com/products/6760200"),
            normalized("https://WWW.MUSINSA.COM./products/6760200"),
        )
    }

    @Test
    fun `다른 상품은 당연히 다른 정체성이다`() {
        assertNotEquals(
            normalized("https://www.musinsa.com/products/6760200"),
            normalized("https://www.musinsa.com/products/6252844"),
        )
    }

    @Test
    fun `저장 상한을 넘는 URL 은 정규화 값이 없다 - 절단은 거짓 정체성이라 하지 않는다`() {
        val longPath = "/product/" + "%EC%A0%80".repeat(300)
        assertNull(NormalizedInput.of(ParseInput.Link(ProductLink.parse("https://unknown-mall.example.com$longPath"))))
        assertNotNull(NormalizedInput.of(ParseInput.Link(ProductLink.parse("https://unknown-mall.example.com/p/1"))))
    }
}
