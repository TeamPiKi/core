package com.depromeet.piki.item.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.product.service.remote.ProductExtractorException
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubImageSnapshotExtractor
import com.depromeet.piki.support.StubImageStorage
import com.depromeet.piki.support.StubProductLinkExtractor
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 소유권 fencing(#802) 검증 — attempt 토큰이 어긋난 좀비 워커의 결과가 전이·ext 호출로 새지 않음을 확인한다.
//
// 비-@Transactional: 시작 가드 검증이 실제 @Async 워커(별도 스레드·트랜잭션)를 태우므로 커밋된 데이터가 필요하다.
// 자기가 만든 행은 격리된 URL·itemId 로 구분해 메서드 끝에서 직접 정리한다(CLAUDE.md '동시성·시간 의존 통합 테스트').
class ParsingHeartbeatIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var itemParsingService: ItemParsingService

    @Autowired private lateinit var parsingOwnership: ParsingOwnership

    @Autowired private lateinit var asyncItemParsingWorker: AsyncItemParsingWorker

    @Autowired private lateinit var asyncImageParsingWorker: AsyncImageParsingWorker

    @Autowired private lateinit var stubProductLinkExtractor: StubProductLinkExtractor

    @Autowired private lateinit var stubImageSnapshotExtractor: StubImageSnapshotExtractor

    @Autowired private lateinit var stubImageStorage: StubImageStorage

    @Autowired private lateinit var itemRepository: ItemRepository

    @Autowired private lateinit var itemSnapshotRepository: ItemSnapshotRepository

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `claim attempt 와 어긋난 결과는 markReady 가 전이하지 않고 폐기한다`() {
        val item = itemRepository.save(Item(ProductLink.parse("https://shop.example.com/products/fence-${UUID.randomUUID()}")))
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()).apply { markProcessing() }) // attempt 0 (집기는 예산 미소모)
        val snapshotId = snapshot.getId()
        try {
            // 소유권이 다른 시도로 넘어가 attempt 2 가 된 상황을 DB 에 반영. updated_at=now 라 배경 recover 가 안 건드린다.
            jdbcTemplate.update("UPDATE item_snapshots SET attempt_count = 2, updated_at = ? WHERE id = ?", LocalDateTime.now(), snapshotId)

            // 옛 시도(attempt 1)의 결과로 markReady → fencing 으로 전이 없이 폐기(좀비 결과).
            val applied =
                itemParsingService.markReady(
                    snapshotId,
                    ProductSnapshot(link = item.link, name = "좀비결과", currentPrice = 1_000, currency = "KRW", imageUrl = "https://img.example.com/z.png"),
                    expectedAttempt = 1,
                )

            // 반환값이 계약이다 — 호출부(특히 이미지 워커의 raw 회수)가 이 값으로 갈리므로, DB 상태와 함께 고정한다.
            assertFalse(applied, "좀비 결과는 '적용되지 않음'(false)으로 보고돼야 한다")
            val reloaded = itemSnapshotRepository.findById(snapshotId) ?: error("행 없음")
            assertEquals(ItemStatus.PROCESSING, reloaded.status, "좀비 결과는 READY 로 전이하면 안 된다")
            assertNull(reloaded.name, "좀비 결과의 추출값이 반영되면 안 된다")
            assertEquals(2, reloaded.attemptCount, "소유권을 쥔 새 시도(attempt 2)는 그대로여야 한다")
        } finally {
            deleteItem(item.getId())
        }
    }

    @Test
    fun `소유권 attempt 가 일치하면 markReady 가 정상 전이한다`() {
        // fencing 대조군 — 어긋날 때만 막고, 일치하면 그대로 전이함을 함께 고정한다. 워커를 태우지 않으므로 stub 세팅은 불필요하다.
        val item = itemRepository.save(Item(ProductLink.parse("https://shop.example.com/products/match-${UUID.randomUUID()}")))
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()).apply { markProcessing() }) // attempt 0 (집기는 예산 미소모)
        val snapshotId = snapshot.getId()
        try {
            // 실제 흐름대로 워커의 소유권 획득(0 -> 1)을 재현한 뒤 그 토큰으로 전이한다.
            val attempt = parsingOwnership.acquire(snapshotId, 0) ?: error("소유권 획득 실패")
            val applied =
                itemParsingService.markReady(
                    snapshotId,
                    ProductSnapshot(link = item.link, name = "정상결과", currentPrice = 2_000, currency = "KRW", imageUrl = "https://img.example.com/ok.png"),
                    expectedAttempt = attempt,
                )

            assertTrue(applied, "소유권이 일치하면 '적용됨'(true)으로 보고돼야 한다")
            val reloaded = itemSnapshotRepository.findById(snapshotId) ?: error("행 없음")
            assertEquals(ItemStatus.READY, reloaded.status)
            assertEquals("정상결과", reloaded.name)
        } finally {
            deleteItem(item.getId())
        }
    }

    @Test
    fun `시작 가드 — 소유권을 잃은 claim 은 워커가 ext 를 호출하지 않고 스킵한다`() {
        val extCalls = AtomicInteger(0)
        stubProductLinkExtractor.build = {
            extCalls.incrementAndGet()
            ProductSnapshot(link = it, name = "호출됨", currentPrice = 1_000, currency = "KRW", imageUrl = "https://img.example.com/c.png")
        }
        val item = itemRepository.save(Item(ProductLink.parse("https://shop.example.com/products/guard-${UUID.randomUUID()}")))
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()).apply { markProcessing() }) // attempt 0 (집기는 예산 미소모)
        val snapshotId = snapshot.getId()

        // 워커의 스킵은 부수효과가 없어 로그가 유일한 완료 신호다 — 그 로그로 완료를 관측해 "ext 미호출"을 결정적으로 단언한다.
        val workerLogger = LoggerFactory.getLogger(AsyncItemParsingWorker::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        workerLogger.addAppender(appender)
        try {
            // 소유권이 넘어가 attempt 2 가 된 뒤, 옛 시도(attempt 1)의 지목이 뒤늦게 워커에 도착한 상황.
            jdbcTemplate.update("UPDATE item_snapshots SET attempt_count = 2, updated_at = ? WHERE id = ?", LocalDateTime.now(), snapshotId)

            // 옛 시도(attempt 1)로 워커 실행 — 시작 가드의 fenced touch 가 0행이라 ext 호출 없이 스킵해야 한다.
            asyncItemParsingWorker.parse(item.getId(), snapshotId, item.link ?: error("link 없음"), 1)

            // 스킵 완료(로그)를 기다린 뒤 ext 가 한 번도 안 불렸음을 단언한다. list 동시 접근 CME 는 ignoreExceptions 로 흡수.
            await().ignoreExceptions().atMost(Duration.ofSeconds(5)).until {
                appender.list.any { it.formattedMessage.contains("item.parse.skip") && it.formattedMessage.contains("snapshot=$snapshotId") }
            }
            assertEquals(0, extCalls.get(), "소유권을 잃은 좀비 워커는 ext 를 호출하면 안 된다")
            // 소유권을 쥔 attempt 2 는 여전히 PROCESSING (좀비가 아무 전이도 안 함).
            assertEquals(ItemStatus.PROCESSING, itemSnapshotRepository.findById(snapshotId)?.status)
        } finally {
            workerLogger.detachAppender(appender)
            deleteItem(item.getId())
        }
    }

    @Test
    fun `추출 도중 소유권을 잃어 좀비가 된 이미지 워커는 raw 원본을 지우지 않는다`() {
        // 이미지 경로에서 좀비 폐기가 조용하면(전이 스킵을 호출부가 모르면) 워커가 자기 결과를 성공으로 착각해
        // raw 를 회수해버린다 — 재클레임된 새 시도가 재실행할 원본을 잃는 데이터 유실 경로다. 그 회귀를 고정한다.
        val imageKey = "items/raw/zombie-${UUID.randomUUID()}.jpg"
        val item = itemRepository.save(Item(sourceImageKey = imageKey))
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()).apply { markProcessing() }) // attempt 0 (집기는 예산 미소모)
        val snapshotId = snapshot.getId()

        // 추출이 도는 사이 소유권이 다른 시도로 넘어간(attempt 2) 상황을 stub 안에서 재현한다 — 시작 시 획득은 성공하고
        // (0 -> 1) 결과 전이 시점에만 소유권이 어긋나는, 죽은 줄 알고 되살린 뒤 옛 워커가 뒤늦게 돌아온 상황의 재현이다.
        stubImageSnapshotExtractor.build = {
            jdbcTemplate.update("UPDATE item_snapshots SET attempt_count = 2, updated_at = ? WHERE id = ?", LocalDateTime.now(), snapshotId)
            StubImageSnapshotExtractor.defaultSnapshot()
        }
        val workerLogger = LoggerFactory.getLogger(AsyncImageParsingWorker::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        workerLogger.addAppender(appender)
        try {
            asyncImageParsingWorker.parse(item.getId(), snapshotId, imageKey, 0)

            // 좀비 폐기 로그가 유일한 완료 신호다(전이·회수를 둘 다 안 하므로 관측할 부수효과가 없다).
            await().ignoreExceptions().atMost(Duration.ofSeconds(5)).until {
                appender.list.any { it.formattedMessage.contains("item ${item.getId()} 이미지 좀비 결과") }
            }
            assertFalse(imageKey in stubImageStorage.deletedKeys, "좀비 워커가 raw 를 지우면 재클레임된 새 시도가 재실행할 원본을 잃는다")
            assertEquals(ItemStatus.PROCESSING, itemSnapshotRepository.findById(snapshotId)?.status, "좀비는 전이도 하지 않아야 한다")
        } finally {
            workerLogger.detachAppender(appender)
            deleteItem(item.getId())
        }
    }

    // 반납의 "PENDING 으로 되돌아간다" 자체는 여기서 통합으로 관측하지 않는다 — 반납된 행은 배경 디스패처가 다음
    // tick(1s)에 곧바로 다시 집어 PROCESSING 으로 만들기 때문에, PENDING 을 보는 창이 스케줄러와 경합해 닫힌다.
    // (그 경합이 곧 반납이 의도대로 동작한다는 방증이다.) 대신 세 곳이 나눠 고정한다:
    //   - 전이·예산 규칙   → ItemSnapshotTest 의 release 단위 테스트
    //   - 워커 경로의 효과 → WishlistRegisterAsyncIntegrationTest 의 "곧바로 재실행되고" (반납이 없으면 그 대기 안에 2회차가 안 온다)
    //   - 상한 분기        → 바로 아래 테스트 (FAILED 는 터미널이라 스케줄러와 경합하지 않는다)

    @Test
    fun `실행 예산을 다 쓴 뒤의 일시 오류는 반납 대신 FAILED 로 종결된다`() {
        // 예산이 소진된 행을 PENDING 으로 되돌리면 디스패처가 다시 집어 무한 재큐잉이 된다. 반납 경로도 되살림 경로와
        // 같은 상한 판정을 거치는지 고정한다.
        val item = itemRepository.save(Item(ProductLink.parse("https://shop.example.com/products/exhaust-${UUID.randomUUID()}")))
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()).apply { markProcessing() })
        val snapshotId = snapshot.getId()
        // 마지막 실행 예산만 남긴 상태에서 진입시킨다 — 워커가 획득하며 +1 해 상한(MAX_ATTEMPTS)에 닿는다.
        jdbcTemplate.update("UPDATE item_snapshots SET attempt_count = ? WHERE id = ?", ItemParsingService.MAX_ATTEMPTS - 1, snapshotId)
        stubProductLinkExtractor.build = { throw ProductExtractorException.transientFailure(null) }
        try {
            asyncItemParsingWorker.parse(item.getId(), snapshotId, item.link!!, ItemParsingService.MAX_ATTEMPTS - 1)

            await().atMost(Duration.ofSeconds(5)).until { itemSnapshotRepository.findById(snapshotId)?.status == ItemStatus.FAILED }
            assertEquals(
                ItemParsingService.MAX_ATTEMPTS,
                itemSnapshotRepository.findById(snapshotId)?.attemptCount,
                "상한에 닿은 실행이므로 예산은 소진된 채 종결돼야 한다",
            )
        } finally {
            deleteItem(item.getId())
        }
    }

    private fun deleteItem(itemId: Long) {
        jdbcTemplate.update("DELETE FROM item_snapshots WHERE item_id = ?", itemId)
        jdbcTemplate.update("DELETE FROM items WHERE id = ?", itemId)
    }
}
