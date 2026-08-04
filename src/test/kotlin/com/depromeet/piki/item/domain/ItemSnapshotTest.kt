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
    fun `추출 전 스냅샷은 status 기본값 PROCESSING 이고 추출 필드가 비어 있어도 생성된다`() {
        val snapshot = ItemSnapshot(itemId = 1L)
        assertEquals(ItemStatus.PROCESSING, snapshot.status)
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
    fun `PROCESSING 스냅샷을 markReady 하면 추출 결과로 채워지고 READY 와 extractedAt 이 설정된다`() {
        val snapshot = ItemSnapshot(itemId = 1L)
        snapshot.markReady(
            ProductSnapshot(name = "나이키", imageUrl = "https://img.example.com/a.png", price = 99_000, currency = "KRW"),
        )
        assertEquals(ItemStatus.READY, snapshot.status)
        assertEquals("나이키", snapshot.name)
        assertEquals(99_000, snapshot.price)
        assertNotNull(snapshot.extractedAt)
    }

    @Test
    fun `markReady 는 추출 경로를 출처로 번역해 기록한다 - 구버전 응답은 미기록`() {
        val fromParser = ItemSnapshot(itemId = 1L)
        fromParser.markReady(
            ProductSnapshot(name = "나이키", imageUrl = "https://img.example.com/a.png", price = 99_000, extractionMethod = "STRUCTURED"),
        )
        assertEquals(ItemSnapshotSource.SERVER, fromParser.source)

        val fromLlm = ItemSnapshot(itemId = 1L)
        fromLlm.markReady(
            ProductSnapshot(name = "나이키", imageUrl = "https://img.example.com/a.png", price = 99_000, extractionMethod = "LLM"),
        )
        assertEquals(ItemSnapshotSource.SERVER_LLM, fromLlm.source)

        val legacy = ItemSnapshot(itemId = 1L)
        legacy.markReady(
            ProductSnapshot(name = "나이키", imageUrl = "https://img.example.com/a.png", price = 99_000),
        )
        assertNull(legacy.source)
    }

    @Test
    fun `markReady 시 name 이 없으면 READY 불변식 위반으로 실패한다`() {
        val snapshot = ItemSnapshot(itemId = 1L)
        assertFailsWith<IllegalArgumentException> {
            snapshot.markReady(ProductSnapshot(price = 1_000, imageUrl = "https://img.example.com/a.png"))
        }
    }

    @Test
    fun `markReady 시 price 가 없으면 READY 불변식 위반으로 실패한다`() {
        val snapshot = ItemSnapshot(itemId = 1L)
        assertFailsWith<IllegalArgumentException> {
            snapshot.markReady(ProductSnapshot(name = "나이키", imageUrl = "https://img.example.com/a.png"))
        }
    }

    @Test
    fun `markReady 시 imageUrl 이 없으면 READY 불변식 위반으로 실패한다`() {
        val snapshot = ItemSnapshot(itemId = 1L)
        assertFailsWith<IllegalArgumentException> {
            snapshot.markReady(ProductSnapshot(name = "나이키", price = 99_000))
        }
    }

    @Test
    fun `PROCESSING 스냅샷을 markFailed 하면 FAILED 가 된다`() {
        val snapshot = ItemSnapshot(itemId = 1L)
        snapshot.markFailed()
        assertEquals(ItemStatus.FAILED, snapshot.status)
    }


    @Test
    fun `PROCESSING 이 아닌 스냅샷을 markReady 하면 IllegalStateException`() {
        val snapshot = ItemSnapshot(itemId = 1L)
        snapshot.markFailed()
        assertFailsWith<IllegalStateException> {
            snapshot.markReady(ProductSnapshot(name = "x", price = 1_000, imageUrl = "https://img.example.com/a.png"))
        }
    }

    // --- 수기 수정(manual) 계약 검증(#825 결정 4) — 새 MANUAL 버전 생성, 기존 행 불변, 병합 400 은 도메인이 직접 던진다 ---

    @Test
    fun `manual 은 base 값 위에 입력을 병합한 READY 새 버전을 만들고 base 는 그대로다`() {
        val base = ItemSnapshot(itemId = 1L)
        base.markReady(ProductSnapshot(name = "나이키", price = 99_000, imageUrl = "https://img.example.com/a.png", currency = "KRW"))
        val editor = java.util.UUID.randomUUID()

        val manual = ItemSnapshot.manual(base = base, name = null, price = 79_000, imageUrl = null, currency = null, editedBy = editor)

        assertEquals(ItemStatus.READY, manual.status)
        assertEquals("나이키", manual.name)
        assertEquals(79_000, manual.price)
        assertEquals("https://img.example.com/a.png", manual.imageUrl)
        assertEquals(ItemSnapshotSource.MANUAL, manual.source)
        assertEquals(editor, manual.editedBy)
        assertNotNull(manual.extractedAt)
        // 기계 버전 불변 — 이력 보존의 핵심.
        assertEquals(99_000, base.price)
        assertEquals(ItemStatus.READY, base.status)
    }

    @Test
    fun `manual 은 상태 제한이 없다 - PENDING·PROCESSING·FAILED base 로도 새 버전을 만든다`() {
        val editor = java.util.UUID.randomUUID()
        listOf(
            ItemSnapshot.pending(itemId = 1L),
            ItemSnapshot(itemId = 1L),
            ItemSnapshot(itemId = 1L).apply { markFailed() },
        ).forEach { base ->
            val manual = ItemSnapshot.manual(
                base = base,
                name = "수기 입력",
                price = 5_000,
                imageUrl = "https://img.example.com/m.png",
                currency = "KRW",
                editedBy = editor,
            )
            assertEquals(ItemStatus.READY, manual.status)
            assertEquals(ItemSnapshotSource.MANUAL, manual.source)
        }
    }

    @Test
    fun `manual 병합 후에도 name 이 비면 ItemException(400)`() {
        val base = ItemSnapshot(itemId = 1L).apply { markFailed() }
        assertFailsWith<ItemException> {
            ItemSnapshot.manual(base = base, name = null, price = 1_000, imageUrl = "https://img.example.com/a.png", currency = "KRW", editedBy = java.util.UUID.randomUUID())
        }
    }

    @Test
    fun `manual 병합 후에도 price 가 없으면 ItemException(400)`() {
        val base = ItemSnapshot(itemId = 1L).apply { markFailed() }
        assertFailsWith<ItemException> {
            ItemSnapshot.manual(base = base, name = "수기", price = null, imageUrl = "https://img.example.com/a.png", currency = "KRW", editedBy = java.util.UUID.randomUUID())
        }
    }

    @Test
    fun `manual 병합 후에도 imageUrl 이 없으면 ItemException(400)`() {
        val base = ItemSnapshot(itemId = 1L).apply { markFailed() }
        assertFailsWith<ItemException> {
            ItemSnapshot.manual(base = base, name = "수기", price = 5_000, imageUrl = null, currency = "KRW", editedBy = java.util.UUID.randomUUID())
        }
    }

    // --- 작업 큐 claim 전이 (PENDING → PROCESSING) ---

    @Test
    fun `pending 팩토리는 PENDING 스냅샷을 만들고 isReady 는 false 다`() {
        val snapshot = ItemSnapshot.pending(itemId = 1L)
        assertEquals(ItemStatus.PENDING, snapshot.status)
        assertFalse(snapshot.isReady())
    }

    @Test
    fun `isInProgress 는 PENDING·PROCESSING 에서 true, READY·FAILED 에서 false 다`() {
        // 수동 새로고침(5단계) 멱등 가드용 — 이미 진행 중이면 새 추출 버전을 만들지 않는다.
        assertTrue(ItemSnapshot.pending(itemId = 1L).isInProgress())
        assertTrue(ItemSnapshot.pending(itemId = 1L).apply { markProcessing() }.isInProgress())
        assertFalse(
            ItemSnapshot(itemId = 1L)
                .apply { markReady(ProductSnapshot(name = "x", price = 1_000, imageUrl = "https://img.example.com/a.png")) }
                .isInProgress(),
        )
        assertFalse(ItemSnapshot(itemId = 1L).apply { markFailed() }.isInProgress())
    }

    @Test
    fun `PENDING 스냅샷을 markProcessing 하면 PROCESSING 이 된다`() {
        val snapshot = ItemSnapshot.pending(itemId = 1L)
        snapshot.markProcessing()
        assertEquals(ItemStatus.PROCESSING, snapshot.status)
    }

    @Test
    fun `PENDING 이 아닌 스냅샷을 markProcessing 하면 IllegalStateException`() {
        // 이미 claim 된(PROCESSING)·완료(READY)·실패(FAILED)는 다시 claim 할 수 없다 — 디스패처 중복 집기 방어.
        assertFailsWith<IllegalStateException> { ItemSnapshot.pending(1L).apply { markProcessing() }.markProcessing() }
        assertFailsWith<IllegalStateException> {
            ItemSnapshot(itemId = 1L)
                .apply { markReady(ProductSnapshot(name = "x", price = 1_000, imageUrl = "https://img.example.com/a.png")) }
                .markProcessing()
        }
        assertFailsWith<IllegalStateException> { ItemSnapshot(itemId = 1L).apply { markFailed() }.markProcessing() }
    }

    // --- 집기·마감 전이 — execution at-least-once (#461) 와 종결 보증 (#802) ---

    @Test
    fun `markProcessing 은 상태만 옮기고 attemptCount 는 건드리지 않는다`() {
        // 집기(claim)는 "워커에게 넘긴다"는 지목일 뿐이다. 시도 소모는 워커가 실행에 진입할 때(소유권 획득) 일어나므로,
        // 집혔지만 제출이 거부돼 실행이 0회인 행이 예산을 잃지 않는다.
        val snapshot = ItemSnapshot.pending(itemId = 1L)
        assertEquals(0, snapshot.attemptCount)
        snapshot.markProcessing()
        assertEquals(ItemStatus.PROCESSING, snapshot.status)
        assertEquals(0, snapshot.attemptCount, "집기는 실행 예산을 소모하지 않는다")
    }

    @Test
    fun `expire 는 PENDING·PROCESSING 을 FAILED 로 종결한다`() {
        // 마감(created_at 기준 상한)은 attempt 예산·박동과 무관한 벽시계라, 아직 집히지 않은 PENDING 도 대상이다.
        assertEquals(ItemStatus.FAILED, ItemSnapshot.pending(1L).apply { expire() }.status)
        assertEquals(
            ItemStatus.FAILED,
            ItemSnapshot.pending(1L).apply {
                markProcessing()
                expire()
            }.status,
        )
    }

    @Test
    fun `이미 종결된 스냅샷을 expire 하면 IllegalStateException`() {
        // READY(완료)·FAILED(실패)는 마감 대상이 아니다 — recover 가 잘못된 행을 집은 코드 버그 방어.
        assertFailsWith<IllegalStateException> {
            ItemSnapshot(itemId = 1L)
                .apply { markReady(ProductSnapshot(name = "x", price = 1_000, imageUrl = "https://img.example.com/a.png")) }
                .expire()
        }
        assertFailsWith<IllegalStateException> { ItemSnapshot(itemId = 1L).apply { markFailed() }.expire() }
    }

    @Test
    fun `release 는 PROCESSING 을 PENDING 으로 되돌리되 소모한 예산은 유지한다`() {
        // 일시 오류로 결론 없이 끝난 실행의 소유권 반납. 예산을 되돌려주면 무한 재시도가 되므로 attemptCount 는 그대로 둔다.
        val snapshot = ItemSnapshot(itemId = 1L, attemptCount = 1)
        snapshot.release()
        assertEquals(ItemStatus.PENDING, snapshot.status)
        assertEquals(1, snapshot.attemptCount, "반납은 소모한 실행 예산을 되돌리지 않는다")
    }

    @Test
    fun `PROCESSING 이 아닌 스냅샷을 release 하면 IllegalStateException`() {
        // 반납은 "실행 중이던 내 소유권을 놓는다"는 뜻이라 PROCESSING 에서만 성립한다.
        assertFailsWith<IllegalStateException> { ItemSnapshot.pending(1L).release() }
        assertFailsWith<IllegalStateException> { ItemSnapshot(itemId = 1L).apply { markFailed() }.release() }
    }
}
