package com.depromeet.piki.product.service

import com.depromeet.piki.product.domain.ProductLink
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProductSnapshotTest {
    @Test
    fun `name 공백은 null 로 정규화된다`() {
        assertNull(ProductSnapshot.fromExtracted("   ", null, 1_000, "KRW").name)
    }

    @Test
    fun `imageUrl 은 https 가 아니면 null 로 정규화된다`() {
        listOf(
            "http://cdn.example.com/a.jpg",
            "//cdn.example.com/a.jpg",
            "data:image/png;base64,xxx",
            "javascript:alert(1)",
            "",
        ).forEach { raw ->
            assertNull(
                ProductSnapshot.fromExtracted("상품", raw, 1_000, "KRW").imageUrl,
                "'$raw' 는 거부되어야 함",
            )
        }
    }

    @Test
    fun `https imageUrl 은 그대로 통과한다`() {
        assertEquals(
            "https://cdn.example.com/a.jpg",
            ProductSnapshot.fromExtracted("상품", "https://cdn.example.com/a.jpg", 1_000, "KRW").imageUrl,
        )
    }

    @Test
    fun `currency 대소문자·공백은 ISO 4217 대문자로 정규화된다`() {
        assertEquals("USD", ProductSnapshot.fromExtracted("상품", null, 1_000, " usd ").currency)
    }

    @Test
    fun `ISO 4217 이 아닌 currency 는 null 로 정규화된다`() {
        assertNull(ProductSnapshot.fromExtracted("상품", null, 1_000, "ZZZ").currency)
    }

    @Test
    fun `가격이 음수면 ProductSnapshotException 을 던진다`() {
        assertFailsWith<ProductSnapshotException> {
            ProductSnapshot.fromExtracted("상품", null, -1, "KRW")
        }
    }

    @Test
    fun `price 가 null 이면 예외 없이 null 로 통과한다`() {
        assertNull(ProductSnapshot.fromExtracted("상품", null, null, "KRW").price)
    }

    @Test
    fun `price 가 0 이면 0 으로 통과한다`() {
        assertEquals(0, ProductSnapshot.fromExtracted("상품", null, 0, "KRW").price)
    }

    @Test
    fun `price 가 양수이면 그대로 통과한다`() {
        assertEquals(1_000, ProductSnapshot.fromExtracted("상품", null, 1_000, "KRW").price)
    }

    @Test
    fun `finalUrl 은 귀결점 규칙(http·https·host)에 맞을 때만 링크로 들고 아니면 null 이다`() {
        assertEquals(
            ProductLink.parse("https://www.musinsa.com/products/1"),
            ProductSnapshot.fromExtracted("상품", null, 1_000, "KRW", finalUrl = "https://www.musinsa.com/products/1").finalUrl,
        )
        assertEquals(
            "http://shop.example.com/p/1",
            ProductSnapshot.fromExtracted("상품", null, 1_000, "KRW", finalUrl = "http://shop.example.com/p/1").finalUrl?.toString(),
        )
        assertNull(ProductSnapshot.fromExtracted("상품", null, 1_000, "KRW", finalUrl = "not a url").finalUrl)
        assertNull(ProductSnapshot.fromExtracted("상품", null, 1_000, "KRW", finalUrl = "ftp://shop.example.com/p/1").finalUrl)
    }
}
