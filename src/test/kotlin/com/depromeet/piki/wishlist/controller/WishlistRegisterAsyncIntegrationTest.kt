package com.depromeet.piki.wishlist.controller

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.common.outbox.OutboxStatus
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.item.service.ItemParseResultRecorder
import com.depromeet.piki.item.service.ItemParsingScheduler
import com.depromeet.piki.notification.domain.NotificationType
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.product.service.ProductSnapshotException
import com.depromeet.piki.product.service.remote.ProductExtractorException
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubImageSnapshotExtractor
import com.depromeet.piki.support.StubImageStorage
import com.depromeet.piki.support.StubLinkSnapshotExtractor
import com.depromeet.piki.support.awaitTicking
import com.depromeet.piki.support.normalizedOf
import com.depromeet.piki.support.deleteWishDataOf
import com.depromeet.piki.support.latestParseOutboxIdOf
import com.depromeet.piki.support.markParseOutboxStuck
import com.depromeet.piki.support.notificationCountOf
import com.depromeet.piki.support.parseOutboxRecoveryCountOf
import com.depromeet.piki.support.parseOutboxStatusOf
import com.depromeet.piki.support.presignImages
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.support.wishItemIdOf
import com.depromeet.piki.user.domain.IdentityType
import io.micrometer.core.instrument.MeterRegistry
import org.awaitility.Awaitility.await
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// @Async 파서는 별도 트랜잭션이라 커밋 후 awaitTicking 으로 기다림
class WishlistRegisterAsyncIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var accessPolicyRepository: com.depromeet.piki.product.routing.DomainAccessPolicyJpaRepository

    @Autowired
    private lateinit var accessPolicy: com.depromeet.piki.product.routing.DbDomainAccessPolicy

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var stubLinkSnapshotExtractor: StubLinkSnapshotExtractor

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
    fun `등록하면 추출을 기다리지 않고 아이템 없이 PARSING 으로 201 이 즉시 반환된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubLinkSnapshotExtractor.build = { ProductSnapshot(name = "나이키 에어포스", price = 99_000) }
            val body = objectMapper.writeValueAsString(mapOf("url" to "https://shop.example.com/products/42"))

            mockMvc
                .perform(
                    post("/api/v1/wishlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                        .content(body),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.data.wish.id").isNumber)
                .andExpect(jsonPath("$.data.item.id").value(nullValue()))
                .andExpect(jsonPath("$.data.item.status").value("PARSING"))
                .andExpect(jsonPath("$.data.item.refreshing").value(false))
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
    fun `등록 후 파싱이 성공하면 위시에 아이템이 확정되고 READY 가 된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubLinkSnapshotExtractor.build = {
                ProductSnapshot(name = "나이키 에어포스", price = 99_000, currency = "KRW", imageUrl = "https://img.example.com/a.png")
            }
            val readyBefore = parseCount("ready", "none")
            val slot = registerAndGetSlot(mockMvc, userId, "https://shop.example.com/products/42")

            itemParsingScheduler.awaitTicking { jdbcTemplate.parseOutboxStatusOf(slot) == OutboxStatus.SUCCEEDED }
            // 파서 비동기라 메트릭 증가도 기다린다
            await().atMost(Duration.ofSeconds(2)).until { parseCount("ready", "none") - readyBefore >= 1.0 }

            val itemId = jdbcTemplate.wishItemIdOf(slot.id) ?: error("wish ${slot.id} 에 아이템이 확정되지 않았다")
            val version = versionsOf(itemId).single()
            assertTrue(version.isReady())
            assertEquals("나이키 에어포스", version.name)
            assertEquals(99_000, version.price)
            assertEquals("KRW", version.currency)
            assertEquals(normalizedOf("https://shop.example.com/products/42").asCanonical(), itemRepository.findById(itemId)?.canonicalUrl)

            mockMvc
                .perform(get("/api/v1/wishlists/${slot.id}").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.item.id").value(itemId))
                .andExpect(jsonPath("$.data.item.status").value("READY"))
                .andExpect(jsonPath("$.data.item.refreshing").value(false))
                .andExpect(jsonPath("$.data.item.name").value("나이키 에어포스"))
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `등록 후 상품 페이지가 아니라고 판정되면 아이템 없이 FAILED 로 종결한다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        // 실패 라인도 latency 를 가져야 타임아웃형 실패가 로그 기반 소요 통계에서 빠지지 않는다(#916)
        val resultLogs = ListAppender<ILoggingEvent>().apply { start() }
        val resultLogger = LoggerFactory.getLogger(ItemParseResultRecorder::class.java) as Logger
        resultLogger.addAppender(resultLogs)
        try {
            stubLinkSnapshotExtractor.build = { throw ProductSnapshotException.notProductPage() }
            val notProductBefore = parseCount("failed", "not_product")
            val slot = registerAndGetSlot(mockMvc, userId, "https://shop.example.com/products/not-a-product")

            itemParsingScheduler.awaitTicking { jdbcTemplate.parseOutboxStatusOf(slot) == OutboxStatus.FAILED }
            await().atMost(Duration.ofSeconds(2)).until { parseCount("failed", "not_product") - notProductBefore >= 1.0 }

            assertNull(jdbcTemplate.wishItemIdOf(slot.id))
            mockMvc
                .perform(get("/api/v1/wishlists/${slot.id}").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.item.id").value(nullValue()))
                .andExpect(jsonPath("$.data.item.status").value("FAILED"))
                .andExpect(jsonPath("$.data.item.sourceUrl").value("https://shop.example.com/products/not-a-product"))
                .andExpect(jsonPath("$.data.priceHistory.length()").value(0))
            // 아웃박스를 고정해 앞선 테스트의 잔류 비동기 로그에 오염되지 않게 한다
            val outboxId = jdbcTemplate.latestParseOutboxIdOf(slot)
            val expectedResultLog =
                Regex("""item\.parse\.result outbox=$outboxId result=failed reason=not_product latency=\d+ms""")
            assertTrue(
                resultLogs.list.any { expectedResultLog.containsMatchIn(it.formattedMessage) },
                "확정 실패의 item.parse.result 구조화 로그도 latency 를 남겨야 한다",
            )
        } finally {
            resultLogger.detachAppender(resultLogs)
            cleanup(userId)
        }
    }

    @Test
    fun `추출이 이름을 못 얻으면 아이템이 확정되고 INCOMPLETE 버전에 얻은 값이 남는다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubLinkSnapshotExtractor.build = { ProductSnapshot(price = 99_000) }
            val incompleteBefore = parseCount("incomplete", "none")
            val slot = registerAndGetSlot(mockMvc, userId, "https://shop.example.com/products/no-name")

            itemParsingScheduler.awaitTicking { jdbcTemplate.parseOutboxStatusOf(slot) == OutboxStatus.SUCCEEDED }
            // 부분 성공은 실패에 섞지 않는다(#506)
            await().atMost(Duration.ofSeconds(2)).until { parseCount("incomplete", "none") - incompleteBefore >= 1.0 }

            val itemId = jdbcTemplate.wishItemIdOf(slot.id) ?: error("wish ${slot.id} 에 아이템이 확정되지 않았다")
            val version = versionsOf(itemId).single()
            assertTrue(version.isIncomplete())
            assertNull(version.name, "못 얻은 필드는 비어 있어 사용자가 채운다")
            assertEquals(99_000, version.price, "얻은 값은 버려지지 않는다")
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `같은 사용자가 같은 URL 을 다시 등록하면 파싱 중에도 확정 뒤에도 409 로 막힌다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubLinkSnapshotExtractor.build = { ProductSnapshot(name = "기본 상품") }
            val body = objectMapper.writeValueAsString(mapOf("url" to "https://shop.example.com/products/9942"))
            val auth = "Bearer ${memberToken(userId)}"

            mockMvc
                .perform(
                    post("/api/v1/wishlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .content(body),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.data.item.status").value("PARSING"))

            mockMvc
                .perform(
                    post("/api/v1/wishlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .content(body),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("WISH-009"))

            val wishId =
                jdbcTemplate.queryForObject("SELECT id FROM wishes WHERE user_id = ?", Long::class.java, uuidToBytes(userId))
                    ?: error("위시가 없다")
            itemParsingScheduler.awaitTicking {
                jdbcTemplate.parseOutboxStatusOf(ItemSlot.wish(wishId)) == OutboxStatus.SUCCEEDED
            }

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
    fun `이미지로 등록하면 파싱 성공 시 링크 없는 아이템이 확정되고 READY 가 된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubImageSnapshotExtractor.build = {
                ProductSnapshot(name = "나이키 에어포스", price = 99_000, currency = "KRW", imageUrl = "https://img.example.com/af.png")
            }
            val (slot, imageKey) = registerImage(mockMvc, userId)

            itemParsingScheduler.awaitTicking { jdbcTemplate.parseOutboxStatusOf(slot) == OutboxStatus.SUCCEEDED }
            val itemId = jdbcTemplate.wishItemIdOf(slot.id) ?: error("wish ${slot.id} 에 아이템이 확정되지 않았다")
            val version = versionsOf(itemId).single()
            assertTrue(version.isReady())
            assertEquals("나이키 에어포스", version.name)
            assertEquals(99_000, version.price)
            assertEquals("KRW", version.currency)
            val item = itemRepository.findById(itemId) ?: error("item $itemId 가 없다")
            assertNull(item.sourceLink())
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `이미지 파싱이 확정 실패(상품 아님)면 아이템 없이 FAILED 로 종결한다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubImageSnapshotExtractor.build = { throw ProductSnapshotException.notProductPage() }
            val (slot, _) = registerImage(mockMvc, userId)

            itemParsingScheduler.awaitTicking { jdbcTemplate.parseOutboxStatusOf(slot) == OutboxStatus.FAILED }
            assertNull(jdbcTemplate.wishItemIdOf(slot.id))
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `이미지 5개를 등록하면 모두 PARSING 으로 반환되고 각각 아이템이 확정된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubImageSnapshotExtractor.build = {
                ProductSnapshot(name = "상품", price = 1_000, imageUrl = "https://img.example.com/p.png")
            }
            val presignResponse =
                mockMvc
                    .perform(
                        post("/api/v1/wishlists/images/presigned")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                            .content(objectMapper.writeValueAsString(presignImages(List(5) { "image/png" }))),
                    ).andExpect(status().isOk)
                    .andReturn()
                    .response
                    .getContentAsString(Charsets.UTF_8)
            val uploads = objectMapper.readTree(presignResponse).path("data").path("uploads")
            val imageKeys = (0 until uploads.size()).map { uploads.path(it).path("imageKey").asText() }
            val response =
                mockMvc
                    .perform(
                        post("/api/v1/wishlists/images/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                            .content(objectMapper.writeValueAsString(mapOf("imageKeys" to imageKeys))),
                    ).andExpect(status().isCreated)
                    .andExpect(jsonPath("$.data.length()").value(5))
                    .andExpect(jsonPath("$.data[0].item.status").value("PARSING"))
                    .andExpect(jsonPath("$.data[4].item.status").value("PARSING"))
                    .andReturn()
                    .response
                    .getContentAsString(Charsets.UTF_8)
            val dataNode = objectMapper.readTree(response).path("data")
            val slots =
                (0 until dataNode.size()).map { i ->
                    ItemSlot.wish(
                        dataNode
                            .path(i)
                            .path("wish")
                            .path("id")
                            .asLong(),
                    )
                }

            itemParsingScheduler.awaitTicking {
                slots.all { jdbcTemplate.parseOutboxStatusOf(it) == OutboxStatus.SUCCEEDED }
            }
            val itemIds = slots.map { jdbcTemplate.wishItemIdOf(it.id) ?: error("wish ${it.id} 에 아이템이 확정되지 않았다") }
            assertEquals(5, itemIds.distinct().size)
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `URL 파싱이 우리 방어 차단으로 확정 실패하면 즉시 FAILED 로 종결하고 internal_error 로 센다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubLinkSnapshotExtractor.build = { throw ProductExtractorException.permanentFailure() }
            val internalErrorBefore = parseCount("failed", "internal_error")
            val slot = registerAndGetSlot(mockMvc, userId, "https://shop.example.com/products/blocked")

            itemParsingScheduler.awaitTicking { jdbcTemplate.parseOutboxStatusOf(slot) == OutboxStatus.FAILED }
            // reason 이 예외에서 파생되므로 파서에서 메트릭까지 배선을 함께 고정한다
            await().atMost(Duration.ofSeconds(2)).until { parseCount("failed", "internal_error") - internalErrorBefore >= 1.0 }

            assertNull(jdbcTemplate.wishItemIdOf(slot.id))
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `URL 파싱이 일시 외부 오류면 재시도 없이 FAILED 로 종결하고 internal_error 로 센다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubLinkSnapshotExtractor.build = { throw ProductExtractorException.transientFailure(null) }
            val internalErrorBefore = parseCount("failed", "internal_error")
            val slot = registerAndGetSlot(mockMvc, userId, "https://shop.example.com/products/transient")

            itemParsingScheduler.awaitTicking { jdbcTemplate.parseOutboxStatusOf(slot) == OutboxStatus.FAILED }
            await().atMost(Duration.ofSeconds(2)).until { parseCount("failed", "internal_error") - internalErrorBefore >= 1.0 }

            assertNull(jdbcTemplate.wishItemIdOf(slot.id))
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `집은 지 2분 넘게 처리 중인 요청은 회수돼 다시 파싱된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubLinkSnapshotExtractor.build = {
                ProductSnapshot(name = "나이키 에어포스", price = 99_000, currency = "KRW", imageUrl = "https://img.example.com/a.png")
            }
            val slot = registerAndGetSlot(mockMvc, userId, "https://shop.example.com/products/stuck")
            jdbcTemplate.markParseOutboxStuck(slot, claimedAgo = Duration.ofMinutes(3))

            itemParsingScheduler.recover()

            assertEquals(OutboxStatus.PENDING, jdbcTemplate.parseOutboxStatusOf(slot))
            assertEquals(1, jdbcTemplate.parseOutboxRecoveryCountOf(slot))
            itemParsingScheduler.awaitTicking { jdbcTemplate.parseOutboxStatusOf(slot) == OutboxStatus.SUCCEEDED }
            assertNotNull(jdbcTemplate.wishItemIdOf(slot.id))
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `회수한 뒤 다시 멈춘 요청은 실패로 닫고 실패 알림을 보낸다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            val internalErrorBefore = parseCount("failed", "internal_error")
            val slot = registerAndGetSlot(mockMvc, userId, "https://shop.example.com/products/stuck-again")
            jdbcTemplate.markParseOutboxStuck(slot, claimedAgo = Duration.ofMinutes(3), recoveryCount = 1)

            itemParsingScheduler.recover()

            assertEquals(OutboxStatus.FAILED, jdbcTemplate.parseOutboxStatusOf(slot))
            assertTrue(parseCount("failed", "internal_error") - internalErrorBefore >= 1.0)
            await().atMost(Duration.ofSeconds(2)).until { jdbcTemplate.notificationCountOf(userId, NotificationType.ITEM_PARSING_FAILED) == 1 }
            mockMvc
                .perform(get("/api/v1/wishlists/${slot.id}").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.item.id").value(nullValue()))
                .andExpect(jsonPath("$.data.item.status").value("FAILED"))
        } finally {
            jdbcTemplate.update("DELETE FROM notifications WHERE user_id = ?", uuidToBytes(userId))
            cleanup(userId)
        }
    }

    @Test
    fun `집은 지 2분이 안 된 처리 중 요청은 회수하지 않는다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            val slot = registerAndGetSlot(mockMvc, userId, "https://shop.example.com/products/still-parsing")
            jdbcTemplate.markParseOutboxStuck(slot, claimedAgo = Duration.ofMinutes(1))

            itemParsingScheduler.recover()

            assertEquals(OutboxStatus.PROCESSING, jdbcTemplate.parseOutboxStatusOf(slot))
            assertEquals(0, jdbcTemplate.parseOutboxRecoveryCountOf(slot))
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `차단 도메인 URL 을 등록하면 등록 시점에 400 으로 거부되고 위시가 생기지 않는다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            // 차단 도메인은 비동기 파싱(FAILED)이 아니라 등록 입력 시점에 동기 400 으로 막는다 — 담기 전에 빠르게 안내한다.
            // 정책 테이블은 비어서 시작하므로(판단하지 않은 것을 미리 채우지 않는다) 이 테스트가 자기 행을 만든다.
            accessPolicyRepository.save(
                com.depromeet.piki.product.routing.DomainAccessPolicyEntity(
                    domain = "kream.co.kr",
                    access = com.depromeet.piki.product.routing.DomainAccess.BLOCKED.name,
                    reason = "테스트: 차단 도메인",
                ),
            )
            accessPolicy.reload()
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

    // 링크 형식·스킴·빈 값·길이의 응답 계약을 HTTP 레벨에서 못박는다. 파싱 위치가 서비스에서 역직렬화로
    // 옮겨가도 클라이언트가 보는 code·detail 이 그대로여야 한다.
    @Test
    fun `잘못된 링크는 사유별 code 로 400 을 받는다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            val cases =
                listOf(
                    // 공백이 든 host 는 URI.create 가 던진다(형식). 스킴 없는 상대 URI 는 통과해 스킴 검증에서 걸린다.
                    Triple("https://exa mple.com/1", "LINK-001", "올바른 링크 형식이 아니에요. 다시 확인해 주세요."),
                    Triple("example.com/products/1", "LINK-002", "https 링크만 등록할 수 있어요."),
                    Triple("http://example.com/products/1", "LINK-002", "https 링크만 등록할 수 있어요."),
                    Triple("", "COMMON-INVALID-INPUT", "링크를 입력해 주세요."),
                    Triple("https://a.com/" + "x".repeat(ProductLink.MAX_LENGTH), "COMMON-INVALID-INPUT", "링크가 너무 길어요."),
                )
            cases.forEach { (url, code, detail) ->
                mockMvc
                    .perform(
                        post("/api/v1/wishlists")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                            .content(objectMapper.writeValueAsString(mapOf("url" to url))),
                    ).andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.code").value(code))
                    .andExpect(jsonPath("$.detail").value(detail))
            }
        } finally {
            cleanup(userId)
        }
    }

    private fun registerAndGetSlot(
        mockMvc: MockMvc,
        userId: UUID,
        url: String,
    ): ItemSlot {
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
        return ItemSlot.wish(
            objectMapper
                .readTree(response)
                .path("data")
                .path("wish")
                .path("id")
                .asLong(),
        )
    }

    // 업로드는 클라가 S3 에 직접 하므로 재현하지 않는다(StubImageStorage.exists 기본값이 "올라왔다")
    private fun registerImage(
        mockMvc: MockMvc,
        userId: UUID,
    ): Pair<ItemSlot, String> {
        val presignResponse =
            mockMvc
                .perform(
                    post("/api/v1/wishlists/images/presigned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                        .content(objectMapper.writeValueAsString(presignImages(listOf("image/png")))),
                ).andExpect(status().isOk)
                .andReturn()
                .response
                .getContentAsString(Charsets.UTF_8)
        val imageKey =
            objectMapper
                .readTree(presignResponse)
                .path("data")
                .path("uploads")
                .path(0)
                .path("imageKey")
                .asText()
        val response =
            mockMvc
                .perform(
                    post("/api/v1/wishlists/images/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}")
                        .content(objectMapper.writeValueAsString(mapOf("imageKeys" to listOf(imageKey)))),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
                .getContentAsString(Charsets.UTF_8)
        val wishId =
            objectMapper
                .readTree(response)
                .path("data")
                .path(0)
                .path("wish")
                .path("id")
                .asLong()
        return ItemSlot.wish(wishId) to imageKey
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

    private fun versionsOf(itemId: Long): List<ItemSnapshot> = itemSnapshotRepository.findAllByItemIds(listOf(itemId))

    // item.parsing 카운터의 현재 값. 공유 컨텍스트라 누적되므로 호출 전후 증가분(delta)으로 단언한다(#468 패턴).
    private fun parseCount(
        result: String,
        reason: String,
    ): Double = meterRegistry.find("item.parsing").tags("result", result, "reason", reason).counter()?.count() ?: 0.0

    private fun cleanup(userId: UUID) {
        jdbcTemplate.deleteWishDataOf(userId)
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(userId))
    }
}
