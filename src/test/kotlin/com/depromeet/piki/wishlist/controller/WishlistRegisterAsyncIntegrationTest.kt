package com.depromeet.piki.wishlist.controller

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.item.service.AsyncItemParser
import com.depromeet.piki.item.service.ItemParsingScheduler
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.product.service.ProductSnapshotException
import com.depromeet.piki.product.service.remote.ProductExtractorException
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubImageSnapshotExtractor
import com.depromeet.piki.support.StubImageStorage
import com.depromeet.piki.support.StubLinkSnapshotExtractor
import com.depromeet.piki.support.awaitTicking
import com.depromeet.piki.support.deleteParseOutboxOf
import com.depromeet.piki.support.presignImages
import com.depromeet.piki.support.uuidToBytes
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

// 등록은 비동기(@Async)다. @Transactional 자동 롤백 패턴으로는 워커(별도 스레드·새 트랜잭션)가
// 미커밋 데이터를 못 보므로, 여기서는 @Transactional 없이 실제 커밋하고 상태 전이를 기다린다(awaitTicking 이 디스패처 tick 을 돌린다).
// (CLAUDE.md '동시성·시간 의존 통합 테스트' 별도 분류.) 자기가 만든 행은 격리 userId 로 구분해 메서드 끝에서 정리한다.
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
    fun `등록하면 추출을 기다리지 않고 PENDING 상태로 201 이 즉시 반환된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubLinkSnapshotExtractor.build = { ProductSnapshot(link = it, name = "나이키 에어포스", price = 99_000) }
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
            stubLinkSnapshotExtractor.build = {
                ProductSnapshot(link = it, name = "나이키 에어포스", price = 99_000, currency = "KRW", imageUrl = "https://img.example.com/a.png")
            }
            val readyBefore = parseCount("ready", "none")
            val itemId = registerAndGetItemId(mockMvc, userId, "https://shop.example.com/products/42")

            itemParsingScheduler.awaitTicking { latestSnapshot(itemId)?.status == ItemStatus.READY }
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
        // 실패 원장 라인도 latency 를 갖는 계약(#916)을 라인 모양으로 고정한다 — 성공만 latency 를 가지면
        // 타임아웃형(느린) 실패가 로그 기반 소요 통계에서 통째로 사라진다.
        val workerLogs = ListAppender<ILoggingEvent>().apply { start() }
        val workerLogger = LoggerFactory.getLogger(AsyncItemParser::class.java) as Logger
        workerLogger.addAppender(workerLogs)
        try {
            // 파싱 결과 실패는 동기 400 이 아니라 FAILED 상태로 남는다 (등록 응답은 이미 201 로 끝났으므로).
            stubLinkSnapshotExtractor.build = { throw ProductSnapshotException.notProductPage() }
            val notProductBefore = parseCount("failed", "not_product")
            val itemId = registerAndGetItemId(mockMvc, userId, "https://shop.example.com/products/not-a-product")

            itemParsingScheduler.awaitTicking { latestSnapshot(itemId)?.status == ItemStatus.FAILED }
            // 결과 메트릭(#506): 상품 아님 확정 실패는 result=failed,reason=not_product 로 +1.
            await().atMost(Duration.ofSeconds(2)).until { parseCount("failed", "not_product") - notProductBefore >= 1.0 }

            val snapshot = latestSnapshot(itemId) ?: error("item $itemId 의 snapshot 이 없다")
            assertEquals(ItemStatus.FAILED, snapshot.status)
            // 실패 항목은 추출 결과가 비어 있다.
            assertNull(snapshot.name)
            // item 을 고정해 앞선 테스트의 잔류 비동기 로그에 오염되지 않게 하고, latency 는 값 형식(\d+ms)까지 조인다.
            val expectedResultLog =
                Regex("""item\.parse\.result item=$itemId result=failed reason=not_product latency=\d+ms""")
            assertTrue(
                workerLogs.list.any { expectedResultLog.containsMatchIn(it.formattedMessage) },
                "확정 실패의 item.parse.result 구조화 로그도 latency 를 남겨야 한다",
            )
        } finally {
            workerLogger.detachAppender(workerLogs)
            cleanup(userId)
        }
    }

    @Test
    fun `추출이 이름을 못 얻으면 item 이 INCOMPLETE 로 전이하고 얻은 값은 남는다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            // isProductPage=true 라도 이름을 못 뽑으면 name 이 비어 온다. 예전에는 READY 불변식(name 필수)에 걸려
            // FAILED 로 떨어졌지만, 이제는 채운 만큼을 남기고 INCOMPLETE 로 안착해 사용자가 나머지를 채운다(#944).
            stubLinkSnapshotExtractor.build = { ProductSnapshot(link = it, price = 99_000) }
            val incompleteBefore = parseCount("incomplete", "none")
            val itemId = registerAndGetItemId(mockMvc, userId, "https://shop.example.com/products/no-name")

            itemParsingScheduler.awaitTicking { latestSnapshot(itemId)?.status == ItemStatus.INCOMPLETE }
            // 결과 메트릭(#506): 부분 성공은 실패에 섞지 않고 result=incomplete,reason=none 으로 +1 한다.
            await().atMost(Duration.ofSeconds(2)).until { parseCount("incomplete", "none") - incompleteBefore >= 1.0 }

            val snapshot = latestSnapshot(itemId) ?: error("item $itemId 의 snapshot 이 없다")
            assertEquals(ItemStatus.INCOMPLETE, snapshot.status)
            assertNull(snapshot.name, "못 얻은 필드는 비어 있어 사용자가 채운다")
            assertEquals(99_000, snapshot.price, "얻은 값은 버려지지 않는다")
            assertNotNull(snapshot.extractedAt)
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
            stubLinkSnapshotExtractor.build = { ProductSnapshot(link = it, name = "기본 상품") }
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
            val itemId = registerImageAndGetItemId(mockMvc, userId)

            itemParsingScheduler.awaitTicking { latestSnapshot(itemId)?.status == ItemStatus.READY }
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
            stubImageSnapshotExtractor.build = { throw ProductSnapshotException.notProductPage() }
            val itemId = registerImageAndGetItemId(mockMvc, userId)

            itemParsingScheduler.awaitTicking { latestSnapshot(itemId)?.status == ItemStatus.FAILED }
            val snapshot = latestSnapshot(itemId) ?: error("item $itemId 의 snapshot 이 없다")
            assertEquals(ItemStatus.FAILED, snapshot.status)
            assertNull(snapshot.name)
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

            itemParsingScheduler.awaitTicking { itemIds.all { latestSnapshot(it)?.status == ItemStatus.READY } }
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
            val itemId = registerImageAndGetItemId(mockMvc, userId)
            itemParsingScheduler.awaitTicking { latestSnapshot(itemId)?.status == ItemStatus.READY }

            // 파싱이 끝나면 등록 시 올린 raw 원본(items/raw/...)을 S3 에서 회수한다(누수 방지, best-effort 라 회수까지 await).
            // 자기 item 의 sourceImageKey 로 특정해 단언하므로 공유 stub 의 다른 테스트 회수와 섞이지 않는다.
            val rawKey = itemRepository.findById(itemId)?.sourceImageKey ?: error("item $itemId 의 sourceImageKey 가 없다")
            await().atMost(Duration.ofSeconds(2)).until { stubImageStorage.deletedKeys.contains(rawKey) }
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `URL 파싱이 대상 차단으로 확정 실패하면 즉시 FAILED 로 종결하고 blocked 로 센다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubLinkSnapshotExtractor.build = { throw ProductExtractorException.blockedByTarget() }
            val blockedBefore = parseCount("failed", "blocked")
            val itemId = registerAndGetItemId(mockMvc, userId, "https://shop.example.com/products/blocked")

            itemParsingScheduler.awaitTicking { latestSnapshot(itemId)?.status == ItemStatus.FAILED }
            // 결과 메트릭(#506·#936): 대상이 막아 확정 실패한 건은 result=failed,reason=blocked 로 +1.
            // reason 이 예외에서 파생되므로(ItemParsingMetrics.reasonOf), 이 단언이 워커→메트릭 배선까지 함께 고정한다.
            await().atMost(Duration.ofSeconds(2)).until { parseCount("failed", "blocked") - blockedBefore >= 1.0 }

            val snapshot = latestSnapshot(itemId) ?: error("item $itemId 의 snapshot 이 없다")
            assertEquals(ItemStatus.FAILED, snapshot.status)
            assertNull(snapshot.name)
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
                    Triple("https://a.com/" + "x".repeat(2048), "COMMON-INVALID-INPUT", "링크가 너무 길어요."),
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

    // 이미지 한 장을 등록하고 그 item id 를 돌려준다 — 발급(presigned) → 확정(confirm) 2단계를 그대로 탄다.
    // 업로드 자체는 클라가 S3 에 직접 하므로 여기선 재현하지 않는다(StubImageStorage.exists 기본값이 "올라왔다").
    private fun registerImageAndGetItemId(
        mockMvc: MockMvc,
        userId: UUID,
    ): Long {
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
            jdbcTemplate.deleteParseOutboxOf(it)
            jdbcTemplate.update("DELETE FROM item_snapshots WHERE item_id IN (${it.joinToString(",")})")
            jdbcTemplate.update("DELETE FROM items WHERE id IN (${it.joinToString(",")})")
        }
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(userId))
    }
}
