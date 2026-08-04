package com.depromeet.piki.product.service

import com.depromeet.piki.product.domain.ProductLink
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProductSnapshotTest {
    private val link = ProductLink.parse("https://shop.example.com/products/42")

    @Test
    fun `name 공백은 null 로 정규화된다`() {
        assertNull(ProductSnapshot.fromExtracted(link, "   ", null, 1_000, "KRW").name)
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
                ProductSnapshot.fromExtracted(link, "상품", raw, 1_000, "KRW").imageUrl,
                "'$raw' 는 거부되어야 함",
            )
        }
    }

    @Test
    fun `https imageUrl 은 그대로 통과한다`() {
        assertEquals(
            "https://cdn.example.com/a.jpg",
            ProductSnapshot.fromExtracted(link, "상품", "https://cdn.example.com/a.jpg", 1_000, "KRW").imageUrl,
        )
    }

    @Test
    fun `currency 대소문자·공백은 ISO 4217 대문자로 정규화된다`() {
        assertEquals("USD", ProductSnapshot.fromExtracted(link, "상품", null, 1_000, " usd ").currency)
    }

    @Test
    fun `ISO 4217 이 아닌 currency 는 null 로 정규화된다`() {
        assertNull(ProductSnapshot.fromExtracted(link, "상품", null, 1_000, "ZZZ").currency)
    }

    @Test
    fun `가격이 음수면 ProductSnapshotException 을 던진다`() {
        assertFailsWith<ProductSnapshotException> {
            ProductSnapshot.fromExtracted(link, "상품", null, -1, "KRW")
        }
    }

    @Test
    fun `name 이 512자를 초과하면 ProductSnapshotException 을 던진다`() {
        assertFailsWith<ProductSnapshotException> {
            ProductSnapshot.fromExtracted(link, "가".repeat(513), null, 1_000, "KRW")
        }
    }

    @Test
    fun `imageUrl 이 2048자를 초과하면 ProductSnapshotException 을 던진다`() {
        assertFailsWith<ProductSnapshotException> {
            ProductSnapshot.fromExtracted(link, "상품", "https://cdn.example.com/" + "a".repeat(2048), 1_000, "KRW")
        }
    }

    @Test
    fun `link 가 null 이어도(이미지 추출 경로) 변환된다`() {
        val snapshot = ProductSnapshot.fromExtracted(null, "상품", null, 1_000, "KRW")
        assertNull(snapshot.link)
        assertEquals("상품", snapshot.name)
    }

    @Test
    fun `price 가 null 이면 예외 없이 null 로 통과한다`() {
        assertNull(ProductSnapshot.fromExtracted(link, "상품", null, null, "KRW").price)
    }

    @Test
    fun `price 가 0 이면 0 으로 통과한다`() {
        assertEquals(0, ProductSnapshot.fromExtracted(link, "상품", null, 0, "KRW").price)
    }

    @Test
    fun `price 가 양수이면 그대로 통과한다`() {
        assertEquals(1_000, ProductSnapshot.fromExtracted(link, "상품", null, 1_000, "KRW").price)
    }
}
