package com.depromeet.piki.wishlist.controller

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.item.service.ItemParsingScheduler
import com.depromeet.piki.item.service.ItemParsingService
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.product.service.ProductSnapshotException
import com.depromeet.piki.product.service.remote.ProductExtractorException
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubImageStorage
import com.depromeet.piki.support.StubImageSnapshotExtractor
import com.depromeet.piki.support.StubProductLinkExtractor
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import io.micrometer.core.instrument.MeterRegistry
import org.awaitility.Awaitility.await
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.slf4j.LoggerFactory
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 등록은 비동기(@Async)다. @Transactional 자동 롤백 패턴으로는 워커(별도 스레드·새 트랜잭션)가
// 미커밋 데이터를 못 보므로, 여기서는 @Transactional 없이 실제 커밋하고 Awaitility 로 상태 전이를 기다린다.
// (CLAUDE.md '동시성·시간 의존 통합 테스트' 별도 분류.) 자기가 만든 행은 격리 userId 로 구분해 메서드 끝에서 정리한다.
class WishlistRegisterAsyncIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var stubProductLinkExtractor: StubProductLinkExtractor

    @Autowired
    private lateinit var stubImageSnapshotExtractor: StubImageSnapshotExtractor

    @Autowired
    private lateinit var itemRepository: ItemRepository

    @Autowired
    private lateinit var itemSnapshotRepository: ItemSnapshotRepository

    @Autowired
    private lateinit var itemParsingScheduler: ItemParsingScheduler

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Autowired
    private lateinit var stubImageStorage: StubImageStorage

    @Test
    fun `등록하면 추출을 기다리지 않고 PENDING 상태로 201 이 즉시 반환된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubProductLinkExtractor.build = { ProductSnapshot(link = it, name = "나이키 에어포스", price = 99_000) }
            val body = objectMapper.writeValueAsString(mapOf("url" to "https://shop.example.com/products/42"))

            mockMvc
                .perform(
                    post("/api/v1/wishlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                        .content(body),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.data.wish.id").isNumber)
                .andExpect(jsonPath("$.data.item.status").value("PENDING"))
                // 파싱 전이라 추출 결과는 아직 비어 있고, 입력으로 받은 sourceUrl 만 채워진다.
                .andExpect(jsonPath("$.data.item.name").value(nullValue()))
                .andExpect(jsonPath("$.data.item.price").value(nullValue()))
                .andExpect(jsonPath("$.data.item.sourceUrl").value("https://shop.example.com/products/42"))
                // 백오피스(source_platforms) 미등록 도메인 — host 에서 유도한 임시 표시명(등록 가능 도메인의 첫 라벨)이 나간다.
                .andExpect(jsonPath("$.data.item.sourcePlatform").value("example"))
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `등록 후 파싱이 성공하면 item 이 READY 로 전이하며 추출 결과가 채워진다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubProductLinkExtractor.build = {
                ProductSnapshot(link = it, name = "나이키 에어포스", price = 99_000, currency = "KRW", imageUrl = "https://img.example.com/a.png")
            }
            val readyBefore = parseCount("ready", "none")
            val itemId = registerAndGetItemId(mockMvc, userId, "https://shop.example.com/products/42")

            await().atMost(Duration.ofSeconds(5)).until {
                latestSnapshot(itemId)?.status == ItemStatus.READY
            }
            // 결과 메트릭(#506): 성공은 result=ready,reason=none 으로 +1 (워커 비동기라 메트릭 증가도 await).
            await().atMost(Duration.ofSeconds(2)).until { parseCount("ready", "none") - readyBefore >= 1.0 }

            // 표시값·상태는 활성 snapshot 이 보유한다(4a) — item 은 정체성(link)만 든다.
            val snapshot = latestSnapshot(itemId) ?: error("item $itemId 의 snapshot 이 없다")
            assertEquals("나이키 에어포스", snapshot.name)
            assertEquals(99_000, snapshot.price)
            assertEquals("KRW", snapshot.currency)
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `등록 후 상품 페이지가 아니라고 판정되면 item 이 FAILED 로 전이한다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            // 파싱 결과 실패는 동기 400 이 아니라 FAILED 상태로 남는다 (등록 응답은 이미 201 로 끝났으므로).
            stubProductLinkExtractor.build = { throw ProductSnapshotException.notProductPage() }
            val notProductBefore = parseCount("failed", "not_product")
            val itemId = registerAndGetItemId(mockMvc, userId, "https://shop.example.com/products/not-a-product")

            await().atMost(Duration.ofSeconds(5)).until {
                latestSnapshot(itemId)?.status == ItemStatus.FAILED
            }
            // 결과 메트릭(#506): 상품 아님 확정 실패는 result=failed,reason=not_product 로 +1.
            await().atMost(Duration.ofSeconds(2)).until { parseCount("failed", "not_product") - notProductBefore >= 1.0 }

            val snapshot = latestSnapshot(itemId) ?: error("item $itemId 의 snapshot 이 없다")
            assertEquals(ItemStatus.FAILED, snapshot.status)
            // 실패 항목은 추출 결과가 비어 있다.
            assertNull(snapshot.name)
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `추출은 됐으나 이름이 비어 있으면 READY 부적격으로 item 이 FAILED 로 전이한다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            // isProductPage=true 라도 이름을 못 뽑으면 name 이 비어 온다. READY 불변식(name 필수)에 걸려
            // markReady 가 거부하고, 워커가 이를 받아 PROCESSING 방치 대신 FAILED 로 떨어뜨린다.
            stubProductLinkExtractor.build = { ProductSnapshot(link = it, price = 99_000) }
            val rejectedBefore = parseCount("failed", "ready_rejected")
            val itemId = registerAndGetItemId(mockMvc, userId, "https://shop.example.com/products/no-name")

            await().atMost(Duration.ofSeconds(5)).until {
                latestSnapshot(itemId)?.status == ItemStatus.FAILED
            }
            // 결과 메트릭(#506): 추출됐으나 READY 부적격(이름 없음)은 result=failed,reason=ready_rejected 로 +1.
            await().atMost(Duration.ofSeconds(2)).until { parseCount("failed", "ready_rejected") - rejectedBefore >= 1.0 }

            val snapshot = latestSnapshot(itemId) ?: error("item $itemId 의 snapshot 이 없다")
            assertEquals(ItemStatus.FAILED, snapshot.status)
            assertNull(snapshot.name)
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `같은 URL 재등록은 공유 정체성 기준 409 로 막혀 wish 가 1건만 남는다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubProductLinkExtractor.build = { ProductSnapshot(link = it, name = "기본 상품") }
            // 이 파일의 다른 테스트와 URL 을 공유하지 않는다 — 정체성 매칭(별칭)이 테스트 간 상태가 되므로 전용 URL 로 격리.
            val body = objectMapper.writeValueAsString(mapOf("url" to "https://shop.example.com/products/9942"))
            val auth = "Bearer ${memberToken(userId)}"

            mockMvc
                .perform(
                    post("/api/v1/wishlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .content(body),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.data.item.status").value("PENDING"))

            // 공유 정체성(#825 활성화) — 같은 사용자가 같은 상품을 다시 담으면 새 카드 대신 409.
            // (옛 dedup 없는 multi-record 모델을 뒤집은 새 계약이다.)
            mockMvc
                .perform(
                    post("/api/v1/wishlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .content(body),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("WISH-009"))

            val wishCount =
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM wishes WHERE user_id = ?",
                    Int::class.java,
                    uuidToBytes(userId),
                )
            assertEquals(1, wishCount)
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `이미지로 등록하면 PENDING 으로 201 즉시 반환 후 파싱 성공 시 READY 로 전이한다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubImageSnapshotExtractor.build = {
                ProductSnapshot(link = null, name = "나이키 에어포스", price = 99_000, currency = "KRW", imageUrl = "https://img.example.com/af.png")
            }
            val image = MockMultipartFile("images", "p.png", "image/png", byteArrayOf(1, 2, 3))
            val itemId = registerImageAndGetItemId(mockMvc, userId, image)

            await().atMost(Duration.ofSeconds(5)).until {
                latestSnapshot(itemId)?.status == ItemStatus.READY
            }
            val snapshot = latestSnapshot(itemId) ?: error("item $itemId 의 snapshot 이 없다")
            assertEquals("나이키 에어포스", snapshot.name)
            assertEquals(99_000, snapshot.price)
            assertEquals("KRW", snapshot.currency)
            // 이미지 등록은 link(원본 URL)가 없다 — link 는 정체성이라 item 에서 읽는다.
            val item = itemRepository.findById(itemId) ?: error("item $itemId 가 없다")
            assertNull(item.link)
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `이미지 파싱이 확정 실패(상품 아님)면 item 이 FAILED 로 전이한다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            // 확정 실패(상품 아님)는 다시 해도 결과가 같아 즉시 FAILED 로 종결한다 (일시 외부 오류는 PROCESSING 유지 — 아래 별도 테스트).
            stubImageSnapshotExtractor.build = { throw ProductSnapshotException.notProductPage() }
            val image = MockMultipartFile("images", "p.png", "image/png", byteArrayOf(1, 2, 3))
            val itemId = registerImageAndGetItemId(mockMvc, userId, image)

            await().atMost(Duration.ofSeconds(5)).until {
                latestSnapshot(itemId)?.status == ItemStatus.FAILED
            }
            val snapshot = latestSnapshot(itemId) ?: error("item $itemId 의 snapshot 이 없다")
            assertEquals(ItemStatus.FAILED, snapshot.status)
            assertNull(snapshot.name)
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `이미지 파싱이 일시 외부 오류면 소유권을 반납해 곧바로 재실행되고 예산을 다 쓴 뒤에야 FAILED 가 된다`() {
        // URL 경로와 동일 — 일시 외부 오류(원격 추출 서비스 5xx 등)는 확정 실패가 아니므로 워커가 즉시 종결하지 않고,
        // 소유권을 반납(PROCESSING→PENDING)해 디스패처가 다음 tick(1s)에 곧바로 다시 집게 한다.
        // 이미지 입력은 S3 raw 로 durable 하므로 재실행이 그 key 로 원본을 다시 읽는다(#461).
        //
        // **재실행이 초 단위로 일어난다는 것 자체가 반납의 증거다** — 반납이 없으면 stale 판정(마지막 박동 + 60s)을
        // 기다려야 해서 이 대기 안에 2회차가 오지 않는다(#802).
        val calls = AtomicInteger(0)
        stubImageSnapshotExtractor.build = {
            calls.incrementAndGet()
            throw ProductExtractorException.transientFailure(RuntimeException("원격 503"))
        }
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            val image = MockMultipartFile("images", "p.png", "image/png", byteArrayOf(1, 2, 3))
            val itemId = registerImageAndGetItemId(mockMvc, userId, image)
            // 반납 → 재집힘이 실제로 돌아 실행 예산(MAX_ATTEMPTS)을 다 쓸 때까지.
            await().atMost(Duration.ofSeconds(20)).until { calls.get() >= ItemParsingService.MAX_ATTEMPTS }
            // 예산을 다 쓴 뒤에야 종결된다 — 첫 일시 오류에 FAILED 로 떨어지지 않는다.
            await().atMost(Duration.ofSeconds(10)).until { latestSnapshot(itemId)?.status == ItemStatus.FAILED }
            assertEquals(ItemParsingService.MAX_ATTEMPTS, latestSnapshot(itemId)?.attemptCount)
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `이미지 5개를 등록하면 모두 PENDING 으로 반환되고 각각 READY 로 전이한다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubImageSnapshotExtractor.build = {
                ProductSnapshot(link = null, name = "상품", price = 1_000, imageUrl = "https://img.example.com/p.png")
            }
            val request = multipart("/api/v1/wishlists/images")
            (1..5).forEach { i ->
                request.file(MockMultipartFile("images", "p$i.png", "image/png", byteArrayOf(i.toByte())))
            }
            request.header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
            val response =
                mockMvc
                    .perform(request)
                    .andExpect(status().isCreated)
                    .andExpect(jsonPath("$.data.length()").value(5))
                    // 등록 직후 응답은 모두 PENDING 이어야 한다 — 이미지도 link 처럼 작업 큐에 적재되고, 서버가 즉시 READY/PROCESSING 을 내리는 회귀를 잡는다.
                    .andExpect(jsonPath("$.data[0].item.status").value("PENDING"))
                    .andExpect(jsonPath("$.data[4].item.status").value("PENDING"))
                    .andReturn()
                    .response
                    .getContentAsString(Charsets.UTF_8)
            val dataNode = objectMapper.readTree(response).path("data")
            val itemIds =
                (0 until dataNode.size()).map { i ->
                    dataNode
                        .path(i)
                        .path("item")
                        .path("id")
                        .asLong()
                }

            await().atMost(Duration.ofSeconds(5)).until {
                itemIds.all { latestSnapshot(it)?.status == ItemStatus.READY }
            }
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `이미지 파싱이 READY 로 끝나면 등록 시 durable 적재한 raw 원본을 회수한다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubImageSnapshotExtractor.build = {
                ProductSnapshot(link = null, name = "상품", price = 1_000, currency = "KRW", imageUrl = "https://img.example.com/p.png")
            }
            val image = MockMultipartFile("images", "p.png", "image/png", byteArrayOf(1, 2, 3))
            val itemId = registerImageAndGetItemId(mockMvc, userId, image)
            await().atMost(Duration.ofSeconds(5)).until { latestSnapshot(itemId)?.status == ItemStatus.READY }

            // 파싱이 끝나면 등록 시 올린 raw 원본(items/raw/...)을 S3 에서 회수한다(누수 방지, best-effort 라 회수까지 await).
            // 자기 item 의 sourceImageKey 로 특정해 단언하므로 공유 stub 의 다른 테스트 회수와 섞이지 않는다.
            val rawKey = itemRepository.findById(itemId)?.sourceImageKey ?: error("item $itemId 의 sourceImageKey 가 없다")
            await().atMost(Duration.ofSeconds(2)).until { stubImageStorage.deletedKeys.contains(rawKey) }
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `link 있는 stale PROCESSING 을 recover 가 재실행해 READY 로 되살린다`() {
        // 디스패처가 집은 직후 워커가 크래시해 **실행 0회**로 PROCESSING 에 갇힌 상황(그래서 attempt 는 0 이다 —
        // 집기는 예산을 소모하지 않는다). recover 가 되살려 완성시킨다 — execution at-least-once 의 핵심(#461).
        stubProductLinkExtractor.build = {
            ProductSnapshot(link = it, name = "되살아난 상품", price = 1_000, currency = "KRW", imageUrl = "https://img.example.com/a.png")
        }
        val item = itemRepository.save(Item(ProductLink.parse("https://shop.example.com/products/revive")))
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()).apply { markProcessing() })
        val itemId = item.getId()
        try {
            // 이 행의 updated_at 만 과거로 밀어 stale 로 만든다. 현실적 threshold(스케줄러의 now-60초)라
            // 다른 테스트가 막 만든 최근 PROCESSING 은 안 건드리고, 이 행만 대상이 된다(공유 컨텍스트 격리).
            jdbcTemplate.update(
                "UPDATE item_snapshots SET updated_at = ? WHERE id = ?",
                LocalDateTime.now().minusSeconds(120),
                snapshot.getId(),
            )

            itemParsingScheduler.recover() // 되살림 지목 + 디스패치 (attempt 는 워커가 실행에 진입할 때 소모)

            await().atMost(Duration.ofSeconds(5)).until { latestSnapshot(itemId)?.status == ItemStatus.READY }
            val recovered = latestSnapshot(itemId) ?: error("item $itemId 의 snapshot 이 없다")
            assertEquals("되살아난 상품", recovered.name)
            // 실행은 되살림으로 시작한 이 한 번뿐이다 — 실행 0회로 갇혔던 초회는 예산을 태우지 않았다.
            assertEquals(1, recovered.attemptCount)
        } finally {
            jdbcTemplate.update("DELETE FROM item_snapshots WHERE item_id = ?", itemId)
            jdbcTemplate.update("DELETE FROM items WHERE id = ?", itemId)
        }
    }

    @Test
    fun `재시도 상한에 도달한 stale PROCESSING 은 recover 가 FAILED 로 종결한다`() {
        // 이미 상한(2회)까지 **실행**된 채 stale — 더 되살리지 않고 종결한다 (무한 재큐잉 방지).
        val item = itemRepository.save(Item(ProductLink.parse("https://shop.example.com/products/exhausted")))
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()).apply { markProcessing() })
        val itemId = item.getId()
        // 종결 구조화 로그(#902)는 알림 룰·대시보드가 소비하는 계약이라 라인 모양까지 여기서 고정한다.
        val terminalLogs = ListAppender<ILoggingEvent>().apply { start() }
        val serviceLogger = LoggerFactory.getLogger(ItemParsingService::class.java) as Logger
        serviceLogger.addAppender(terminalLogs)
        try {
            jdbcTemplate.update(
                "UPDATE item_snapshots SET attempt_count = 2, updated_at = ? WHERE id = ?",
                LocalDateTime.now().minusSeconds(120),
                snapshot.getId(),
            )

            val exhaustedBefore = parseCount("failed", "retry_exhausted")
            itemParsingScheduler.recover() // attempt 2 >= 2 → FAILED (재실행 없음, 동기 종결)

            assertEquals(ItemStatus.FAILED, latestSnapshot(itemId)?.status)
            // 결과 메트릭(#506): recover 가 재시도 상한 소진으로 종결 → result=failed,reason=retry_exhausted +1 (recover 동기라 즉시 단언).
            assertTrue(parseCount("failed", "retry_exhausted") - exhaustedBefore >= 1.0, "retry_exhausted 메트릭이 증가해야 한다")
            assertTrue(
                terminalLogs.list.any {
                    it.formattedMessage.contains("item.parse.result") &&
                        it.formattedMessage.contains("result=failed") &&
                        it.formattedMessage.contains("reason=retry_exhausted") &&
                        it.formattedMessage.contains("url=shop.example.com/products/exhausted")
                },
                "종결 시 item.parse.result 구조화 로그(url 포함)를 남겨야 한다",
            )
        } finally {
            serviceLogger.detachAppender(terminalLogs)
            jdbcTemplate.update("DELETE FROM item_snapshots WHERE item_id = ?", itemId)
            jdbcTemplate.update("DELETE FROM items WHERE id = ?", itemId)
        }
    }

    @Test
    fun `imageKey 있는 stale PROCESSING 은 recover 가 재실행해 READY 로 되살린다`() {
        // 이미지 경로도 원본을 S3 raw 로 durable 적재하므로(link 와 대칭), 크래시로 stale 된 PROCESSING 을 recover 가 재실행해
        // 워커가 그 key 로 원본을 다시 읽어 완성시킨다 — 메모리 ByteArray 시절의 "이미지는 복구 불가" 비대칭이 사라졌다(#461).
        stubImageSnapshotExtractor.build = {
            ProductSnapshot(link = null, name = "되살아난 이미지", price = 2_000, currency = "KRW", imageUrl = "https://img.example.com/revive.png")
        }
        val item = itemRepository.save(Item(sourceImageKey = "items/raw/${UUID.randomUUID()}.png"))
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()).apply { markProcessing() })
        val itemId = item.getId()
        try {
            jdbcTemplate.update(
                "UPDATE item_snapshots SET updated_at = ? WHERE id = ?",
                LocalDateTime.now().minusSeconds(120),
                snapshot.getId(),
            )

            itemParsingScheduler.recover() // 되살림 지목 + 디스패치 (attempt 는 워커가 실행에 진입할 때 소모)

            await().atMost(Duration.ofSeconds(5)).until { latestSnapshot(itemId)?.status == ItemStatus.READY }
            val recovered = latestSnapshot(itemId) ?: error("item $itemId 의 snapshot 이 없다")
            assertEquals("되살아난 이미지", recovered.name)
            // link 경로와 대칭 — 실행 0회로 갇혔던 초회는 예산을 태우지 않았으므로 실행은 한 번뿐이다.
            assertEquals(1, recovered.attemptCount)
        } finally {
            jdbcTemplate.update("DELETE FROM item_snapshots WHERE item_id = ?", itemId)
            jdbcTemplate.update("DELETE FROM items WHERE id = ?", itemId)
        }
    }

    @Test
    fun `link·imageKey 둘 다 없는 orphan stale PROCESSING 은 recover 가 FAILED 로 종결한다`() {
        // 정상 흐름엔 없는 "입력 없는 행"(영속화 경로가 깨진 신호) — 되살릴 입력이 없으므로 attempt 와 무관하게 종결한다.
        val item = itemRepository.save(Item(link = null))
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()).apply { markProcessing() })
        val itemId = item.getId()
        // 종결 구조화 로그(#902) — url 없는(입력 부재) 종결도 같은 계약의 라인을 남긴다.
        val terminalLogs = ListAppender<ILoggingEvent>().apply { start() }
        val serviceLogger = LoggerFactory.getLogger(ItemParsingService::class.java) as Logger
        serviceLogger.addAppender(terminalLogs)
        try {
            jdbcTemplate.update(
                "UPDATE item_snapshots SET updated_at = ? WHERE id = ?",
                LocalDateTime.now().minusSeconds(120),
                snapshot.getId(),
            )

            val noSourceBefore = parseCount("failed", "no_source")
            itemParsingScheduler.recover() // 입력 없음 → FAILED

            assertEquals(ItemStatus.FAILED, latestSnapshot(itemId)?.status)
            // 결과 메트릭(#506): 되살릴 입력 없음 종결 → result=failed,reason=no_source +1.
            assertTrue(parseCount("failed", "no_source") - noSourceBefore >= 1.0, "no_source 메트릭이 증가해야 한다")
            assertTrue(
                terminalLogs.list.any {
                    it.formattedMessage.contains("item.parse.result") &&
                        it.formattedMessage.contains("result=failed") &&
                        it.formattedMessage.contains("reason=no_source")
                },
                "입력 없는 종결도 item.parse.result 구조화 로그를 남겨야 한다",
            )
        } finally {
            serviceLogger.detachAppender(terminalLogs)
            jdbcTemplate.update("DELETE FROM item_snapshots WHERE item_id = ?", itemId)
            jdbcTemplate.update("DELETE FROM items WHERE id = ?", itemId)
        }
    }

    @Test
    fun `URL 파싱이 일시 외부 오류면 소유권을 반납해 곧바로 재실행되고 예산을 다 쓴 뒤에야 FAILED 가 된다`() {
        // 일시 외부 오류(원격 추출 서비스 5xx·연결 실패 등)는 확정 실패가 아니므로 워커가 즉시 종결하지 않고, 소유권을
        // 반납(PROCESSING→PENDING)해 디스패처가 다음 tick(1s)에 다시 집게 한다. 종결 판정은 여전히 서비스 몫이다(#461).
        //
        // **재실행이 초 단위로 일어난다는 것 자체가 반납의 증거다** — 반납이 없으면 stale 판정(마지막 박동 + 60s)을
        // 기다려야 해서 이 대기 안에 2회차가 오지 않는다(#802).
        val calls = AtomicInteger(0)
        stubProductLinkExtractor.build = {
            calls.incrementAndGet()
            throw ProductExtractorException.transientFailure(RuntimeException("원격 503"))
        }
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        // 종결 구조화 로그(#902) — release 예산 소진 종결도 같은 계약의 라인을 남긴다 (이 라인은 url 없이 남는 경로).
        val terminalLogs = ListAppender<ILoggingEvent>().apply { start() }
        val serviceLogger = LoggerFactory.getLogger(ItemParsingService::class.java) as Logger
        serviceLogger.addAppender(terminalLogs)
        try {
            val itemId = registerAndGetItemId(mockMvc, userId, "https://shop.example.com/products/transient")
            // 반납 → 재집힘이 실제로 돌아 실행 예산(MAX_ATTEMPTS)을 다 쓸 때까지.
            await().atMost(Duration.ofSeconds(20)).until { calls.get() >= ItemParsingService.MAX_ATTEMPTS }
            // 예산을 다 쓴 뒤에야 종결된다 — 첫 일시 오류에 FAILED 로 떨어지지 않는다.
            await().atMost(Duration.ofSeconds(10)).until { latestSnapshot(itemId)?.status == ItemStatus.FAILED }
            assertEquals(ItemParsingService.MAX_ATTEMPTS, latestSnapshot(itemId)?.attemptCount)
            assertTrue(
                terminalLogs.list.any {
                    it.formattedMessage.contains("item.parse.result") &&
                        it.formattedMessage.contains("result=failed") &&
                        it.formattedMessage.contains("reason=retry_exhausted")
                },
                "release 예산 소진 종결도 item.parse.result 구조화 로그를 남겨야 한다",
            )
        } finally {
            serviceLogger.detachAppender(terminalLogs)
            cleanup(userId)
        }
    }

    @Test
    fun `URL 파싱이 영구 외부 오류(차단된 호스트·접근 불가)면 즉시 FAILED 로 종결한다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            // 재시도해도 결정론적으로 재실패하는 영구 오류(원격 422 확정 실패)는 recover 를 기다리지 않고
            // (약 150초 헛돔 방지) 워커가 즉시 FAILED 로 종결한다. recover 는 stale(60초) 후에야 돌므로 5초 내 FAILED 면 즉시 종결이다.
            stubProductLinkExtractor.build = { throw ProductExtractorException.permanentFailure() }
            val permanentBefore = parseCount("failed", "permanent_error")
            val itemId = registerAndGetItemId(mockMvc, userId, "https://shop.example.com/products/blocked")

            await().atMost(Duration.ofSeconds(5)).until {
                latestSnapshot(itemId)?.status == ItemStatus.FAILED
            }
            // 결과 메트릭(#506): 재시도 무의미한 영구 외부 오류 확정 실패는 result=failed,reason=permanent_error 로 +1.
            await().atMost(Duration.ofSeconds(2)).until { parseCount("failed", "permanent_error") - permanentBefore >= 1.0 }

            val snapshot = latestSnapshot(itemId) ?: error("item $itemId 의 snapshot 이 없다")
            assertEquals(ItemStatus.FAILED, snapshot.status)
            assertNull(snapshot.name)
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `미지원 플랫폼(KREAM) URL 을 등록하면 등록 시점에 400 으로 거부되고 위시가 생기지 않는다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            // 미지원 플랫폼은 비동기 파싱(FAILED)이 아니라 등록 입력 시점에 동기 400 으로 막는다 — 담기 전에 빠르게 안내한다.
            val body = objectMapper.writeValueAsString(mapOf("url" to "https://kream.co.kr/products/950123"))

            mockMvc
                .perform(
                    post("/api/v1/wishlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                        .content(body),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("LINK-003"))
                .andExpect(jsonPath("$.detail").value("아직 지원하지 않는 쇼핑몰이에요. 상품 이미지를 직접 등록해 주세요."))

            // 등록 자체가 막혀 위시가 생기지 않는다(파싱 큐 적재 전 차단).
            val wishCount =
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM wishes WHERE user_id = ?",
                    Int::class.java,
                    uuidToBytes(userId),
                )
            assertEquals(0, wishCount)
        } finally {
            cleanup(userId)
        }
    }

    private fun registerAndGetItemId(
        mockMvc: MockMvc,
        userId: UUID,
        url: String,
    ): Long {
        val body = objectMapper.writeValueAsString(mapOf("url" to url))
        val response =
            mockMvc
                .perform(
                    post("/api/v1/wishlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                        .content(body),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
                .getContentAsString(Charsets.UTF_8)
        return objectMapper
            .readTree(response)
            .path("data")
            .path("item")
            .path("id")
            .asLong()
    }

    private fun registerImageAndGetItemId(
        mockMvc: MockMvc,
        userId: UUID,
        image: MockMultipartFile,
    ): Long {
        val response =
            mockMvc
                .perform(
                    multipart("/api/v1/wishlists/images")
                        .file(image)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
                .getContentAsString(Charsets.UTF_8)
        return objectMapper
            .readTree(response)
            .path("data")
            .path(0)
            .path("item")
            .path("id")
            .asLong()
    }

    private fun buildMockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun insertMember(userId: UUID) {
        jdbcTemplate.update(
            "INSERT INTO users (id, nickname, identity_type, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
            uuidToBytes(userId),
            userId.toString().take(10),
            "MEMBER",
        )
    }

    private fun memberToken(userId: UUID): String = jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)

    // 표시값·상태는 item 의 활성(최신) snapshot 이 보유한다(4a). 폴링·단언이 이 snapshot 을 읽는다.
    private fun latestSnapshot(itemId: Long): ItemSnapshot? = itemSnapshotRepository.findLatestByItemId(itemId)

    // item.parsing 카운터의 현재 값. 공유 컨텍스트라 누적되므로 호출 전후 증가분(delta)으로 단언한다(#468 패턴).
    private fun parseCount(
        result: String,
        reason: String,
    ): Double = meterRegistry.find("item.parsing").tags("result", result, "reason", reason).counter()?.count() ?: 0.0

    // @Transactional 자동 롤백이 없으므로 이 테스트가 만든 user·wish·item·snapshot 을 직접 정리한다.
    private fun cleanup(userId: UUID) {
        // wishes 는 item_id 를 더 들지 않는다(4b 정규화) — snapshot_id 로 item_snapshots 를 조인해 itemId 에 도달한다.
        val itemIds =
            jdbcTemplate.queryForList(
                "SELECT s.item_id FROM wishes w JOIN item_snapshots s ON s.id = w.snapshot_id WHERE w.user_id = ?",
                Long::class.java,
                uuidToBytes(userId),
            )
        jdbcTemplate.update("DELETE FROM wishes WHERE user_id = ?", uuidToBytes(userId))
        itemIds.takeIf { it.isNotEmpty() }?.let {
            // 별칭(item_links)도 함께 지운다 — 남기면 다음 실행에서 stale 별칭이 삭제된 item 을 가리켜
            // 공유 정체성 매칭(resolveExistingItem)이 null 로 빠지고 재등록 409 계약 검증이 어긋난다.
            jdbcTemplate.update("DELETE FROM item_links WHERE item_id IN (${it.joinToString(",")})")
            jdbcTemplate.update("DELETE FROM item_snapshots WHERE item_id IN (${it.joinToString(",")})")
            jdbcTemplate.update("DELETE FROM items WHERE id IN (${it.joinToString(",")})")
        }
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(userId))
    }
}
