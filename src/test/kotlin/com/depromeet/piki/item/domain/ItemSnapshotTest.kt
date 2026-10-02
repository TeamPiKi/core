package com.depromeet.piki.item.domain

import com.depromeet.piki.product.service.ProductSnapshot
import org.springframework.http.HttpStatus
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.UUID

class ItemSnapshotTest {
    @Test
    fun `추출 필드가 모두 채워진 READY 스냅샷을 생성한다`() {
        val snapshot = ItemSnapshot(
            itemId = 1L,
            name = "나이키 에어포스",
            imageUrl = "https://img.example.com/a.png",
            price = 99_000,
            currency = "KRW",
            status = ItemStatus.READY,
            extractedAt = LocalDateTime.of(2026, 6, 3, 12, 0),
        )
        assertEquals(1L, snapshot.itemId)
        assertEquals("나이키 에어포스", snapshot.name)
        assertEquals(99_000, snapshot.price)
        assertEquals(ItemStatus.READY, snapshot.status)
    }

    @Test
    fun `추출 전 스냅샷은 status 기본값 PENDING 이고 추출 필드가 비어 있어도 생성된다`() {
        val snapshot = ItemSnapshot(itemId = 1L)
        assertEquals(ItemStatus.PENDING, snapshot.status)
        assertNull(snapshot.name)
        assertNull(snapshot.price)
        assertNull(snapshot.imageUrl)
        assertNull(snapshot.currency)
        assertNull(snapshot.extractedAt)
    }

    @Test
    fun `price 가 음수면 생성에 실패한다`() {
        assertFailsWith<IllegalArgumentException> {
            ItemSnapshot(itemId = 1L, price = -1)
        }
    }

    @Test
    fun `name 이 512자를 초과하면 생성에 실패한다`() {
        assertFailsWith<IllegalArgumentException> {
            ItemSnapshot(itemId = 1L, name = "가".repeat(513))
        }
    }

    @Test
    fun `imageUrl 이 2048자를 초과하면 생성에 실패한다`() {
        assertFailsWith<IllegalArgumentException> {
            ItemSnapshot(itemId = 1L, imageUrl = "h".repeat(2049))
        }
    }

    @Test
    fun `currency 가 8자를 초과하면 생성에 실패한다`() {
        assertFailsWith<IllegalArgumentException> {
            ItemSnapshot(itemId = 1L, currency = "123456789")
        }
    }

    @Test
    fun `경계값 — name 512자·currency 8자·price 0 은 허용된다`() {
        val snapshot = ItemSnapshot(
            itemId = 1L,
            name = "가".repeat(512),
            currency = "12345678",
            price = 0,
        )
        assertEquals(512, snapshot.name?.length)
        assertEquals(8, snapshot.currency?.length)
        assertEquals(0, snapshot.price)
    }

    // --- 전이 (2단계: item 평행 추적) ---

    @Test
    fun `PENDING 스냅샷을 markExtracted 하면 추출 결과로 채워지고 READY 와 extractedAt 이 설정된다`() {
        val snapshot = ItemSnapshot.pending(itemId = 1L, requestedBy = UUID.randomUUID())
        val status =
            snapshot.markExtracted(
                ProductSnapshot(name = "나이키", imageUrl = "https://img.example.com/a.png", price = 99_000, currency = "KRW"),
            )
        assertEquals(ItemStatus.READY, status)
        assertEquals(ItemStatus.READY, snapshot.status)
        assertEquals("나이키", snapshot.name)
        assertEquals(99_000, snapshot.price)
        assertNotNull(snapshot.extractedAt)
    }

    @Test
    fun `markExtracted 는 추출 경로를 출처로 번역해 기록한다 - 구버전 응답은 미기록`() {
        val fromParser = ItemSnapshot.pending(itemId = 1L, requestedBy = UUID.randomUUID())
        fromParser.markExtracted(
            ProductSnapshot(name = "나이키", imageUrl = "https://img.example.com/a.png", price = 99_000, extractionMethod = "STRUCTURED"),
        )
        assertEquals(ItemSnapshotSource.SERVER, fromParser.source)

        val fromLlm = ItemSnapshot.pending(itemId = 1L, requestedBy = UUID.randomUUID())
        fromLlm.markExtracted(
            ProductSnapshot(name = "나이키", imageUrl = "https://img.example.com/a.png", price = 99_000, extractionMethod = "LLM"),
        )
        assertEquals(ItemSnapshotSource.SERVER_LLM, fromLlm.source)

        val legacy = ItemSnapshot.pending(itemId = 1L, requestedBy = UUID.randomUUID())
        legacy.markExtracted(
            ProductSnapshot(name = "나이키", imageUrl = "https://img.example.com/a.png", price = 99_000),
        )
        assertNull(legacy.source)
    }

    @Test
    fun `markExtracted 시 name 이 없으면 INCOMPLETE 로 전이하고 얻은 값은 채워진다`() {
        val snapshot = ItemSnapshot.pending(itemId = 1L, requestedBy = UUID.randomUUID())
        val status = snapshot.markExtracted(ProductSnapshot(price = 1_000, imageUrl = "https://img.example.com/a.png"))
        assertEquals(ItemStatus.INCOMPLETE, status)
        assertEquals(ItemStatus.INCOMPLETE, snapshot.status)
        assertEquals(1_000, snapshot.price)
        assertEquals("https://img.example.com/a.png", snapshot.imageUrl)
        assertNull(snapshot.name)
        assertNotNull(snapshot.extractedAt)
    }

    @Test
    fun `markExtracted 시 price 가 없으면 INCOMPLETE 로 전이한다 - 사진에 가격이 없는 정상 입력이다`() {
        val snapshot = ItemSnapshot.pending(itemId = 1L, requestedBy = UUID.randomUUID())
        val status = snapshot.markExtracted(ProductSnapshot(name = "나이키", imageUrl = "https://img.example.com/a.png"))
        assertEquals(ItemStatus.INCOMPLETE, status)
        assertEquals("나이키", snapshot.name)
        assertNull(snapshot.price)
    }

    @Test
    fun `markExtracted 시 imageUrl 이 없으면 INCOMPLETE 로 전이한다`() {
        val snapshot = ItemSnapshot.pending(itemId = 1L, requestedBy = UUID.randomUUID())
        val status = snapshot.markExtracted(ProductSnapshot(name = "나이키", price = 99_000))
        assertEquals(ItemStatus.INCOMPLETE, status)
        assertNull(snapshot.imageUrl)
    }

    @Test
    fun `markExtracted 가 값을 하나도 못 얻으면 FAILED 로 전이하고 extractedAt 도 남기지 않는다`() {
        val snapshot = ItemSnapshot.pending(itemId = 1L, requestedBy = UUID.randomUUID())
        val status = snapshot.markExtracted(ProductSnapshot(currency = "KRW"))
        assertEquals(ItemStatus.FAILED, status)
        assertEquals(ItemStatus.FAILED, snapshot.status)
        assertNull(snapshot.extractedAt)
    }

    @Test
    fun `markExtracted 는 blank name 을 값으로 세지 않아 나머지가 없으면 FAILED 다`() {
        val snapshot = ItemSnapshot.pending(itemId = 1L, requestedBy = UUID.randomUUID())
        assertEquals(ItemStatus.FAILED, snapshot.markExtracted(ProductSnapshot(name = "   ")))
    }

    @Test
    fun `INCOMPLETE 는 READY 취급을 받지 못한다`() {
        val snapshot = ItemSnapshot.pending(itemId = 1L, requestedBy = UUID.randomUUID())
        snapshot.markExtracted(ProductSnapshot(name = "나이키", imageUrl = "https://img.example.com/a.png"))
        assertFalse(snapshot.isReady())
        assertTrue(snapshot.isIncomplete())
        assertFalse(snapshot.isFailed())
        assertFalse(snapshot.isInProgress())
    }

    @Test
    fun `PENDING 스냅샷을 markFailed 하면 FAILED 가 된다`() {
        val snapshot = ItemSnapshot.pending(itemId = 1L, requestedBy = UUID.randomUUID())
        snapshot.markFailed()
        assertEquals(ItemStatus.FAILED, snapshot.status)
    }


    @Test
    fun `PENDING 이 아닌 스냅샷을 markExtracted 하면 IllegalStateException`() {
        val snapshot = ItemSnapshot.pending(itemId = 1L, requestedBy = UUID.randomUUID())
        snapshot.markFailed()
        assertFailsWith<IllegalStateException> {
            snapshot.markExtracted(ProductSnapshot(name = "x", price = 1_000, imageUrl = "https://img.example.com/a.png"))
        }
    }

    // --- 수기 수정(manual) 계약 검증(#825 결정 4) — 새 MANUAL 버전 생성, 기존 행 불변, 병합 400 은 도메인이 직접 던진다 ---

    @Test
    fun `manual 은 base 값 위에 입력을 병합한 READY 새 버전을 만들고 base 는 그대로다`() {
        val base = ItemSnapshot.pending(itemId = 1L, requestedBy = UUID.randomUUID())
        base.markExtracted(ProductSnapshot(name = "나이키", price = 99_000, imageUrl = "https://img.example.com/a.png", currency = "KRW"))
        val editor = java.util.UUID.randomUUID()

        val manual = ItemSnapshot.manual(base = base, name = null, price = 79_000, imageUrl = null, currency = null, createdBy = editor)

        assertEquals(ItemStatus.READY, manual.status)
        assertEquals("나이키", manual.name)
        assertEquals(79_000, manual.price)
        assertEquals("https://img.example.com/a.png", manual.imageUrl)
        assertEquals(ItemSnapshotSource.MANUAL, manual.source)
        assertEquals(editor, manual.createdBy)
        assertNotNull(manual.extractedAt)
        // 기계 버전 불변 — 이력 보존의 핵심.
        assertEquals(99_000, base.price)
        assertEquals(ItemStatus.READY, base.status)
    }

    // INCOMPLETE 의 완성 경로 — 추출이 못 채운 필드를 사용자가 채우면 READY 가 된다(#944). 이 시나리오가 곧
    // "채울 수 있는 만큼 채워 내려주고 나머지는 사용자가" 의 계약이라, 전이와 수기 수정이 맞물리는 지점을 고정한다.
    @Test
    fun `INCOMPLETE 를 base 로 빈 필드를 채우면 READY 새 버전이 되고 base 는 그대로다`() {
        val base = ItemSnapshot.pending(itemId = 1L, requestedBy = UUID.randomUUID())
        base.markExtracted(ProductSnapshot(name = "몬치치 인형", imageUrl = "https://img.example.com/a.png"))
        assertEquals(ItemStatus.INCOMPLETE, base.status)

        val manual =
            ItemSnapshot.manual(
                base = base,
                name = null,
                price = 25_000,
                imageUrl = null,
                currency = "KRW",
                createdBy = java.util.UUID.randomUUID(),
            )

        assertEquals(ItemStatus.READY, manual.status)
        assertEquals("몬치치 인형", manual.name, "추출이 건진 값은 사용자가 다시 입력하지 않아도 병합된다")
        assertEquals(25_000, manual.price)
        assertEquals(ItemSnapshotSource.MANUAL, manual.source)
        assertEquals(ItemStatus.INCOMPLETE, base.status, "기계 버전은 불변 — 이력으로 남는다")
    }

    @Test
    fun `INCOMPLETE base 에 채워도 여전히 빈 필드가 남으면 ItemException(400)`() {
        val base = ItemSnapshot.pending(itemId = 1L, requestedBy = UUID.randomUUID())
        base.markExtracted(ProductSnapshot(imageUrl = "https://img.example.com/a.png"))
        assertFailsWith<ItemException> {
            ItemSnapshot.manual(base = base, name = "몬치치", price = null, imageUrl = null, currency = null, createdBy = java.util.UUID.randomUUID())
        }
    }

    @Test
    fun `manual 은 상태 제한이 없다 - PENDING·PROCESSING·FAILED base 로도 새 버전을 만든다`() {
        val editor = java.util.UUID.randomUUID()
        listOf(
            ItemSnapshot.pending(itemId = 1L, requestedBy = UUID.randomUUID()),
            ItemSnapshot(itemId = 1L),
            ItemSnapshot.pending(itemId = 1L, requestedBy = UUID.randomUUID()).apply { markFailed() },
        ).forEach { base ->
            val manual = ItemSnapshot.manual(
                base = base,
                name = "수기 입력",
                price = 5_000,
                imageUrl = "https://img.example.com/m.png",
                currency = "KRW",
                createdBy = editor,
            )
            assertEquals(ItemStatus.READY, manual.status)
            assertEquals(ItemSnapshotSource.MANUAL, manual.source)
        }
    }

    @Test
    fun `manual 병합 후에도 name 이 비면 ItemException(400)`() {
        val base = ItemSnapshot.pending(itemId = 1L, requestedBy = UUID.randomUUID()).apply { markFailed() }
        assertFailsWith<ItemException> {
            ItemSnapshot.manual(base = base, name = null, price = 1_000, imageUrl = "https://img.example.com/a.png", currency = "KRW", createdBy = java.util.UUID.randomUUID())
        }
    }

    @Test
    fun `manual 병합 후에도 price 가 없으면 ItemException(400)`() {
        val base = ItemSnapshot.pending(itemId = 1L, requestedBy = UUID.randomUUID()).apply { markFailed() }
        assertFailsWith<ItemException> {
            ItemSnapshot.manual(base = base, name = "수기", price = null, imageUrl = "https://img.example.com/a.png", currency = "KRW", createdBy = java.util.UUID.randomUUID())
        }
    }

    @Test
    fun `manual 병합 후에도 imageUrl 이 없으면 ItemException(400)`() {
        val base = ItemSnapshot.pending(itemId = 1L, requestedBy = UUID.randomUUID()).apply { markFailed() }
        assertFailsWith<ItemException> {
            ItemSnapshot.manual(base = base, name = "수기", price = 5_000, imageUrl = null, currency = "KRW", createdBy = java.util.UUID.randomUUID())
        }
    }

    @Test
    fun `pending 팩토리는 PENDING 스냅샷을 만들고 isReady 는 false 다`() {
        val snapshot = ItemSnapshot.pending(itemId = 1L, requestedBy = UUID.randomUUID())
        assertEquals(ItemStatus.PENDING, snapshot.status)
        assertFalse(snapshot.isReady())
    }

    @Test
    fun `isInProgress 는 PENDING 에서 true, READY·FAILED 에서 false 다`() {
        // 수동 새로고침(5단계) 멱등 가드용 — 이미 진행 중이면 새 추출 버전을 만들지 않는다.
        assertTrue(ItemSnapshot.pending(itemId = 1L, requestedBy = UUID.randomUUID()).isInProgress())
        assertFalse(
            ItemSnapshot.pending(itemId = 1L, requestedBy = UUID.randomUUID())
                .apply { markExtracted(ProductSnapshot(name = "x", price = 1_000, imageUrl = "https://img.example.com/a.png")) }
                .isInProgress(),
        )
        assertFalse(ItemSnapshot.pending(itemId = 1L, requestedBy = UUID.randomUUID()).apply { markFailed() }.isInProgress())
    }
}
