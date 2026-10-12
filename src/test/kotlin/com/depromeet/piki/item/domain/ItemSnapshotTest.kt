package com.depromeet.piki.item.domain

import com.depromeet.piki.contracts.extraction.v1.ExtractionMethod
import com.depromeet.piki.product.service.ProductSnapshot
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ItemSnapshotTest {
    @Test
    fun `price 가 음수면 생성에 실패한다`() {
        assertFailsWith<IllegalArgumentException> { version(price = -1) }
    }

    @Test
    fun `name 이 512자를 초과하면 생성에 실패한다`() {
        assertFailsWith<IllegalArgumentException> { version(name = "가".repeat(513)) }
    }

    @Test
    fun `imageUrl 이 2048자를 초과하면 생성에 실패한다`() {
        assertFailsWith<IllegalArgumentException> { version(imageUrl = "h".repeat(2049)) }
    }

    @Test
    fun `currency 가 8자를 초과하면 생성에 실패한다`() {
        assertFailsWith<IllegalArgumentException> { version(currency = "123456789") }
    }

    @Test
    fun `name 512자 currency 8자 price 0 은 경계값이라 허용된다`() {
        val snapshot = version(name = "가".repeat(512), currency = "12345678", price = 0)

        assertEquals(512, snapshot.name?.length)
        assertEquals(8, snapshot.currency?.length)
        assertEquals(0, snapshot.price)
    }

    @Test
    fun `이름 가격 이미지가 모두 있으면 READY 다`() {
        val snapshot = version()

        assertTrue(snapshot.isReady())
        assertFalse(snapshot.isIncomplete())
    }

    @Test
    fun `이름 가격 이미지 중 하나라도 없으면 INCOMPLETE 다`() {
        listOf(
            version(name = null),
            version(price = null),
            version(imageUrl = null),
        ).forEach { snapshot ->
            assertFalse(snapshot.isReady())
            assertTrue(snapshot.isIncomplete())
        }
    }

    @Test
    fun `blank name 은 값으로 세지 않는다`() {
        assertTrue(version(name = "   ").isIncomplete())
    }

    @Test
    fun `통화가 없어도 이름 가격 이미지가 있으면 READY 다`() {
        assertTrue(version(currency = null).isReady())
    }

    @Test
    fun `extracted 는 추출값과 요청자로 버전을 만든다`() {
        val slot = UUID.randomUUID()

        val snapshot =
            ItemSnapshot.extracted(
                itemId = 1L,
                extracted = ProductSnapshot(name = "나이키", imageUrl = "https://img.example.com/a.png", price = 99_000, currency = "KRW"),
                createdBy = slot,
            )

        assertEquals(1L, snapshot.itemId)
        assertEquals("나이키", snapshot.name)
        assertEquals(99_000, snapshot.price)
        assertEquals("KRW", snapshot.currency)
        assertEquals(slot, snapshot.createdBy)
        assertTrue(snapshot.isReady())
    }

    @Test
    fun `extracted 는 추출 경로를 그대로 들고 사용자 입력이 아니다`() {
        listOf(ExtractionMethod.STRUCTURED, ExtractionMethod.LLM, null).forEach { method ->
            val snapshot =
                ItemSnapshot.extracted(
                    itemId = 1L,
                    extracted = ProductSnapshot(name = "나이키", imageUrl = "https://img.example.com/a.png", price = 99_000, method = method),
                    createdBy = UUID.randomUUID(),
                )
            assertEquals(method, snapshot.method)
            assertFalse(snapshot.userInput)
        }
    }

    @Test
    fun `extracted 는 일부 값만 있어도 INCOMPLETE 버전을 만든다`() {
        val snapshot =
            ItemSnapshot.extracted(
                itemId = 1L,
                extracted = ProductSnapshot(price = 1_000, imageUrl = "https://img.example.com/a.png"),
                createdBy = UUID.randomUUID(),
            )

        assertTrue(snapshot.isIncomplete())
        assertNull(snapshot.name)
        assertEquals(1_000, snapshot.price)
    }

    @Test
    fun `칸 길이를 넘는 추출 결과로는 버전을 만들 수 없다`() {
        listOf(
            ProductSnapshot(name = "가".repeat(ItemSnapshot.NAME_MAX_LENGTH + 1), price = 1_000),
            ProductSnapshot(name = "상품", imageUrl = "https://cdn.example.com/" + "a".repeat(ItemSnapshot.IMAGE_URL_MAX_LENGTH)),
        ).forEach { extracted ->
            assertFalse(ItemSnapshot.accepts(extracted))
            assertFailsWith<IllegalArgumentException> {
                ItemSnapshot.extracted(itemId = 1L, extracted = extracted, createdBy = UUID.randomUUID())
            }
        }
    }

    @Test
    fun `값이 하나도 없는 추출 결과로는 버전을 만들 수 없다`() {
        listOf(ProductSnapshot(currency = "KRW"), ProductSnapshot(name = "   ")).forEach { extracted ->
            assertFalse(ItemSnapshot.accepts(extracted))
            assertFailsWith<IllegalArgumentException> {
                ItemSnapshot.extracted(itemId = 1L, extracted = extracted, createdBy = UUID.randomUUID())
            }
        }
    }

    @Test
    fun `userInput 은 base 값 위에 입력을 병합한 READY 새 버전을 만들고 base 는 그대로다`() {
        val base = version(name = "나이키", price = 99_000, currency = "KRW")
        val editor = UUID.randomUUID()

        val userVersion = ItemSnapshot.userInput(1L, UserInputValues.of(UserInput(name = null, price = 79_000, imageUrl = null, currency = null).filledFrom(base)), createdBy = editor)

        assertTrue(userVersion.isReady())
        assertEquals("나이키", userVersion.name)
        assertEquals(79_000, userVersion.price)
        assertEquals(base.imageUrl, userVersion.imageUrl)
        assertEquals("KRW", userVersion.currency)
        assertTrue(userVersion.userInput)
        assertNull(userVersion.method)
        assertEquals(editor, userVersion.createdBy)
        assertEquals(99_000, base.price)
    }

    // 추출이 못 채운 필드를 사용자가 채우면 READY 가 된다는 INCOMPLETE 의 완성 경로(#944)
    @Test
    fun `INCOMPLETE base 의 빈 필드를 채우면 추출이 건진 값과 병합된 READY 버전이 된다`() {
        val base = version(name = "몬치치 인형", price = null)

        val userVersion = ItemSnapshot.userInput(1L, UserInputValues.of(UserInput(name = null, price = 25_000, imageUrl = null, currency = "KRW").filledFrom(base)), createdBy = UUID.randomUUID())

        assertTrue(userVersion.isReady())
        assertEquals("몬치치 인형", userVersion.name)
        assertEquals(25_000, userVersion.price)
        assertTrue(base.isIncomplete())
    }

    @Test
    fun `base 가 없으면 입력만으로 READY 버전을 만든다`() {
        val userVersion =
            ItemSnapshot.userInput(
                1L,
                UserInputValues.of(UserInput(name = "수기 입력", price = 5_000, imageUrl = "https://img.example.com/m.png", currency = "KRW")),
                createdBy = UUID.randomUUID(),
            )

        assertTrue(userVersion.isReady())
        assertTrue(userVersion.userInput)
    }

    @Test
    fun `사용자 입력 병합 후에도 이름 가격 이미지 중 하나가 비면 ItemException 이다`() {
        val inputs =
            listOf(
                Triple(null, 1_000, "https://img.example.com/a.png"),
                Triple("수기", null, "https://img.example.com/a.png"),
                Triple("수기", 5_000, null),
            )

        inputs.forEach { (name, price, imageUrl) ->
            assertFailsWith<ItemException> {
                UserInputValues.of(UserInput(name = name, price = price, imageUrl = imageUrl, currency = "KRW"))
            }
        }
    }

    private fun version(
        name: String? = "나이키 에어포스",
        price: Int? = 99_000,
        imageUrl: String? = "https://img.example.com/a.png",
        currency: String? = "KRW",
        userInput: Boolean = false,
        method: ExtractionMethod? = ExtractionMethod.STRUCTURED.takeUnless { userInput },
    ): ItemSnapshot =
        ItemSnapshot(
            itemId = 1L,
            name = name,
            imageUrl = imageUrl,
            price = price,
            currency = currency,
            method = method,
            userInput = userInput,
            createdBy = UUID.randomUUID(),
        )
}
