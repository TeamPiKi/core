package com.depromeet.piki.item.domain

import com.depromeet.piki.product.domain.CanonicalLink
import com.depromeet.piki.product.domain.ProductLink
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ItemLinkTest {
    @Test
    fun `CanonicalLink 산출물로 만든 별칭은 유효하다`() {
        val canonical = CanonicalLink.of(ProductLink.parse("https://www.musinsa.com/products/6760200"))
        val link = ItemLink(url = canonical.url, urlHash = canonical.hash, itemId = 1L)
        assertEquals(canonical.hash, link.urlHash)
    }

    @Test
    fun `hash 길이가 64자가 아니면 호출부 버그로 본다`() {
        assertFailsWith<IllegalArgumentException> {
            ItemLink(url = "https://www.musinsa.com/products/1", urlHash = "short", itemId = 1L)
        }
    }

    @Test
    fun `빈 url 은 호출부 버그로 본다`() {
        assertFailsWith<IllegalArgumentException> {
            ItemLink(url = " ", urlHash = "a".repeat(64), itemId = 1L)
        }
    }
}
