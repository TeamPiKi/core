package com.depromeet.piki.product.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

// 정규화는 "어디까지를 같은 상품으로 볼 것인가"의 경계 그 자체라, 몰별 규칙의 실측 근거(2026-07-30)를
// 여기 케이스로 박제한다 — 규칙을 바꾸려면 이 테스트가 먼저 그 근거를 되묻게 된다.
class CanonicalLinkTest {
    private fun canonical(raw: String): CanonicalLink = CanonicalLink.of(ProductLink.parse(raw))

    // --- override 몰: 쿼리 전부 제거 (상품 번호가 경로에 있음을 실측) ---

    @Test
    fun `무신사 onelink 귀결점의 공유자 추적 쿼리가 전부 떨어져 맨 URL 과 같은 정체성이 된다`() {
        val dirty =
            canonical(
                "https://www.musinsa.com/products/6760200?source_caller=sdk&is_retargeting=true" +
                    "&shortlink=hxnauj24&af_channel=mobile_share&af_referrer_uid=1588832660726-6009526" +
                    "&pid=af_app_invites&af_referrer_customer_id=jsy0714",
            )
        val bare = canonical("https://www.musinsa.com/products/6760200")
        assertEquals(bare, dirty)
        assertEquals(bare.hash, dirty.hash)
    }

    @Test
    fun `29cm 의 reward_key 는 denylist 이름이 아니지만 override 라 떨어진다 - 공유자마다 다른 토큰이 정체성을 가르지 못한다`() {
        val fromShareA = canonical("https://www.29cm.co.kr/products/3915971?reward_key=RK_AAAA&utm_source=29cm_pdp_share")
        val fromShareB = canonical("https://www.29cm.co.kr/products/3915971?reward_key=RK_BBBB")
        assertEquals(fromShareA, fromShareB)
    }

    @Test
    fun `29cm 카테고리 탐색 쿼리가 떨어져 브라우저 복사 URL 과 같은 정체성이 된다`() {
        val browsed =
            canonical(
                "https://www.29cm.co.kr/products/3863006?categoryLargeCode=269100100&categoryMediumCode=269101100&categorySmallCode=",
            )
        assertEquals(canonical("https://www.29cm.co.kr/products/3863006"), browsed)
    }

    @Test
    fun `지그재그 단축 귀결점 경로가 웹 경로로 접혀 클릭마다 바뀌는 쿼리와 무관하게 같은 정체성이 된다`() {
        // event_uuid 는 클릭마다, referrer_timestamp 는 밀리초마다 새 값 — 정규화 없이는 같은 링크의
        // 재파싱끼리도 갈라진다(실측). 경로 접기(/p/{id} → /catalog/products/{id})까지 겹쳐 검증한다.
        val fromShortlink =
            canonical(
                "https://zigzag.kr/p/164171173?event_uuid=390edeb9-cc26-414f&referrer_timestamp=1785321370803" +
                    "&utm_source=sharelink&channel=sharelink&deeplink_url=zigzag%3A%2F%2Fopen",
            )
        val fromWeb = canonical("https://zigzag.kr/catalog/products/164171173")
        assertEquals(fromWeb, fromShortlink)
    }

    @Test
    fun `onelink 단축 자체도 쿼리가 떨어진다 - 경로가 곧 공유 코드라 쿼리는 상품을 식별하지 않는다`() {
        val withUtm = canonical("https://musinsa.onelink.me/PvkC/hxnauj24?utm_source=kakao")
        assertEquals(canonical("https://musinsa.onelink.me/PvkC/hxnauj24"), withUtm)
    }

    @ParameterizedTest
    @CsvSource(
        "https://kream.co.kr/products/984835?foo=bar, https://kream.co.kr/products/984835",
        "https://m.a-bly.com/goods/70768193?abc=1, https://m.a-bly.com/goods/70768193",
        "https://s.zigzag.kr/pby6JjF2dc?x=1, https://s.zigzag.kr/pby6JjF2dc",
    )
    fun `크림 에이블리 지그재그단축은 서브도메인 포함 도메인 단위로 override 가 적용된다`(
        dirty: String,
        bare: String,
    ) {
        assertEquals(canonical(bare), canonical(dirty))
    }

    // --- 기본 규칙 몰: 식별 쿼리 보존 + 추적 이름만 제거 ---

    @Test
    fun `cafe24 계열은 쿼리가 곧 상품 번호라 보존된다 - 전부 제거하면 그 몰의 모든 상품이 하나로 합쳐지는 사고`() {
        val a = canonical("https://m.reetkeem.com/product/detail.html?product_no=9275&cate_no=65")
        val b = canonical("https://m.reetkeem.com/product/detail.html?product_no=9090&cate_no=65")
        assertNotEquals(a, b)
    }

    @Test
    fun `미확인 몰도 추적 파라미터는 이름으로 떨어진다 - 광고에서 복사한 URL 의 fbclid 와 utm`() {
        val fromAd =
            canonical(
                "https://cosymosy.co.kr/shop_view/?idx=4360&utm_source=instagram&utm_medium=paid&fbclid=PAdGRleASVDk1",
            )
        assertEquals(canonical("https://cosymosy.co.kr/shop_view/?idx=4360"), fromAd)
    }

    @Test
    fun `남는 쿼리는 이름순 정렬되어 파라미터 순서만 다른 URL 이 같은 정체성이 된다`() {
        val a = canonical("https://m.reetkeem.com/product/detail.html?product_no=9275&cate_no=65")
        val b = canonical("https://m.reetkeem.com/product/detail.html?cate_no=65&product_no=9275")
        assertEquals(a, b)
    }

    // --- 전역 규칙 ---

    @Test
    fun `fragment 는 떨어진다 - HTTP 요청에 실리지 않아 서버 렌더 몰에서 상품을 바꿀 수 없다`() {
        val withFragment = canonical("https://m.reetkeem.com/product/loose-unbal-tee/9277/category/65/display/1/#none")
        val bare = canonical("https://m.reetkeem.com/product/loose-unbal-tee/9277/category/65/display/1/")
        assertEquals(bare, withFragment)
    }

    @Test
    fun `trailing slash 유무로 정체성이 갈리지 않는다`() {
        assertEquals(
            canonical("https://shopsoez.com/product/boat-neck/72"),
            canonical("https://shopsoez.com/product/boat-neck/72/"),
        )
    }

    @Test
    fun `host 대소문자와 trailing dot 이 정규화된다`() {
        assertEquals(
            canonical("https://www.musinsa.com/products/6760200"),
            canonical("https://WWW.MUSINSA.COM./products/6760200"),
        )
    }

    @Test
    fun `다른 상품은 당연히 다른 정체성이다`() {
        assertNotEquals(
            canonical("https://www.musinsa.com/products/6760200"),
            canonical("https://www.musinsa.com/products/6252844"),
        )
    }

    @Test
    fun `hash 는 정규화된 url 의 SHA-256 hex 64자다`() {
        val link = canonical("https://www.musinsa.com/products/6760200")
        assertEquals(64, link.hash.length)
        // 알고리즘 고정 벡터 — 구현이 바뀌어 기존 저장 hash 와 어긋나면 모든 별칭 매칭이 조용히 깨지므로 박제한다.
        assertEquals(
            "1a1bd981e9668435e3b3c58067afdb111ac601eeaa9e28751ae27a2befab8b08",
            link.hash,
        )
    }
}
