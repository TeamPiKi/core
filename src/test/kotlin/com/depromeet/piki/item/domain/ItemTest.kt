package com.depromeet.piki.item.domain

import com.depromeet.piki.product.domain.CanonicalLink
import com.depromeet.piki.product.domain.ProductLink
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ItemTest {
    private fun canonical(raw: String): CanonicalLink = CanonicalLink.of(ProductLink.parse(raw))

    @Test
    fun `파싱 전 item 은 canonical 이 비어 있고 claimCanonical 로 확정된다`() {
        val item = Item(link = ProductLink.parse("https://musinsa.onelink.me/PvkC/hxnauj24"))
        assertNull(item.canonicalHash)

        val canonical = canonical("https://www.musinsa.com/products/6760200")
        item.claimCanonical(canonical)

        assertEquals(canonical.url, item.canonicalUrl)
        assertEquals(canonical.hash, item.canonicalHash)
    }

    @Test
    fun `같은 귀결점 재확정은 멱등이다 - 병합 경합에서 진 쪽의 재시도가 무해해야 한다`() {
        val item = Item(link = ProductLink.parse("https://www.musinsa.com/products/6760200"))
        val canonical = canonical("https://www.musinsa.com/products/6760200")
        item.claimCanonical(canonical)
        item.claimCanonical(canonical)
        assertEquals(canonical.hash, item.canonicalHash)
    }

    @Test
    fun `다른 귀결점으로 재확정하면 코드 버그로 본다 - 정체성은 불변이다`() {
        val item = Item(link = ProductLink.parse("https://www.musinsa.com/products/6760200"))
        item.claimCanonical(canonical("https://www.musinsa.com/products/6760200"))
        assertFailsWith<IllegalStateException> {
            item.claimCanonical(canonical("https://www.musinsa.com/products/6252844"))
        }
    }

    @Test
    fun `link 와 sourceImageKey 를 동시에 가질 수 없다`() {
        assertFailsWith<IllegalArgumentException> {
            Item(link = ProductLink.parse("https://www.musinsa.com/products/1"), sourceImageKey = "raw/abc.jpg")
        }
    }
}
