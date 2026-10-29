package com.depromeet.piki.item.service

import com.depromeet.piki.image.domain.ProductImageKey
import com.depromeet.piki.item.domain.ItemException
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.product.domain.ParseInputType
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.normalizedOf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

// 정체 URL 조회는 unique 색인을 타므로 DB 로 검증한다
@Transactional
class ItemResolverIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var itemResolver: ItemResolver

    @Test
    fun `처음 보는 링크는 귀결점을 정체 URL 로 가진 새 아이템이 된다`() {
        val shortUrl = "https://musinsa.onelink.me/PvkC/resolver0001"
        val finalUrl = "https://www.musinsa.com/products/2000001?af_referrer_customer_id=abc&shortlink=resolver0001"

        val item = itemResolver.resolve(link(shortUrl), ProductLink.parse(finalUrl))

        assertEquals(normalizedOf("https://www.musinsa.com/products/2000001").asCanonical(), item.canonicalUrl)
    }

    @Test
    fun `귀결점이 없어도 등록 URL 이 이미 있는 정체 URL 이면 그 아이템을 재사용한다`() {
        val url = "https://www.musinsa.com/products/2000002"
        val first = itemResolver.resolve(link(url), ProductLink.parse(url))

        val second = itemResolver.resolve(link("$url?utm_source=kakao"), null)

        assertEquals(first.getId(), second.getId())
    }

    @Test
    fun `등록 URL 이 달라도 귀결점이 같으면 그 아이템을 재사용한다`() {
        val finalUrl = "https://www.musinsa.com/products/2000003"
        val first = itemResolver.resolve(link("https://musinsa.onelink.me/PvkC/resolver0002"), ProductLink.parse(finalUrl))

        val second = itemResolver.resolve(link("https://musinsa.onelink.me/PvkC/resolver0003"), ProductLink.parse(finalUrl))

        assertEquals(first.getId(), second.getId())
    }

    @Test
    fun `귀결점이 없으면 등록 URL 이 정체 URL 이 된다`() {
        val url = "https://www.29cm.co.kr/products/2000004"

        val item = itemResolver.resolve(link(url), null)

        assertEquals(normalizedOf(url).asCanonical(), item.canonicalUrl)
    }

    // 절단된 정규화 값은 다른 상품과 충돌할 수 있는 거짓 정체성이다(#843)
    @Test
    fun `저장 상한을 넘는 귀결점은 쓰지 않고 등록 URL 이 정체 URL 이 된다`() {
        val url = "https://unknown-mall.example.com/p/2000006"
        val oversize = "https://unknown-mall.example.com/product/" + "%EC%A0%80".repeat(300)

        val item = itemResolver.resolve(link(url), ProductLink.parse(oversize))

        assertEquals(normalizedOf(url).asCanonical(), item.canonicalUrl)
    }

    @Test
    fun `귀결점과 등록 URL 이 모두 저장 상한을 넘으면 정체 값 없는 아이템을 만들지 않고 실패한다`() {
        val oversize = "https://unknown-mall.example.com/product/" + "%EC%A0%80".repeat(300)

        assertFailsWith<IllegalStateException> { itemResolver.resolve(link(oversize), ProductLink.parse(oversize)) }
    }

    @Test
    fun `이미지 입력은 이미지 키를 정체 값으로 가진 아이템이 되고 쇼핑몰 링크는 없다`() {
        val key = "items/raw/${UUID.randomUUID()}.png"

        val item = itemResolver.resolve(ParseInput.Image(ProductImageKey.parse(key)), null)

        assertEquals(ParseInputType.IMAGE, item.sourceType)
        assertEquals(key, item.canonicalUrl.value)
        assertNull(item.sourceLink())
        assertFailsWith<ItemException> { item.refreshInput() }
    }

    @Test
    fun `같은 이미지 키는 같은 아이템이고 다른 키는 다른 아이템이다`() {
        val key = "items/raw/${UUID.randomUUID()}.png"
        val first = itemResolver.resolve(ParseInput.Image(ProductImageKey.parse(key)), null)

        assertEquals(first.getId(), itemResolver.resolve(ParseInput.Image(ProductImageKey.parse(key)), null).getId())
        assertNotEquals(first.getId(), itemResolver.resolve(ParseInput.Image(ProductImageKey.newRaw("png")), null).getId())
    }

    private fun link(url: String): ParseInput = ParseInput.Link(ProductLink.parse(url))

}
