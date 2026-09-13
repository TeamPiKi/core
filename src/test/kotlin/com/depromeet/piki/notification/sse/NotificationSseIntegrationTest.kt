package com.depromeet.piki.notification.sse

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.notification.controller.dto.NotificationSsePayload
import com.depromeet.piki.notification.domain.Notification
import com.depromeet.piki.notification.domain.NotificationErrorCode
import com.depromeet.piki.notification.domain.NotificationKind
import com.depromeet.piki.notification.domain.NotificationRouting
import com.depromeet.piki.notification.domain.NotificationType
import com.depromeet.piki.notification.repository.NotificationJpaRepository
import com.depromeet.piki.notification.repository.NotificationRepository
import com.depromeet.piki.notification.service.NotificationChannel
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.user.domain.IdentityType
import jakarta.persistence.EntityManager
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// 구독 엔드포인트 contract 와 채널 전달을 실제 빈으로 검증한다.
// 도메인 publish -> AFTER_COMMIT -> dispatcher -> 채널 리스트 순회는 토대(PR #288)의 통합 테스트가 이미 덮으므로,
// 여기서는 SseNotificationChannel 이 그 리스트에 합류하는지 + 채널이 등록 emitter 에 올바로 전달하는지에 집중한다.
// 레지스트리는 인메모리 싱글톤이라 @Transactional 롤백 대상이 아니므로, 각 테스트가 랜덤 userId 를 쓰고 자기 등록분을 정리한다.
@Transactional
class NotificationSseIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Autowired private lateinit var registry: SseEmitterRegistry

    @Autowired private lateinit var sseNotificationChannel: SseNotificationChannel

    @Autowired private lateinit var localDelivery: LocalSseDelivery

    @Autowired private lateinit var channels: List<NotificationChannel>

    @Autowired private lateinit var notificationRepository: NotificationRepository

    @Autowired private lateinit var notificationJpaRepository: NotificationJpaRepository

    @Autowired private lateinit var entityManager: EntityManager

    @Autowired private lateinit var objectMapper: ObjectMapper

    private fun authHeader(userId: UUID): String =
        "Bearer ${jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)}"

    private fun buildMockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    @Test
    fun `토큰 없이 구독하면 401 이 ApiResponseBody contract 로 내려간다`() {
        buildMockMvc()
            .perform(get("/api/v1/notifications/subscribe"))
            .andExpect(status().isUnauthorized)
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.detail", notNullValue()))
    }

    @Test
    fun `SSE 구독이 끝나며 일어나는 async 재디스패치는 인가에서 거부되지 않는다`() {
        val userId = UUID.randomUUID()
        val mockMvc = buildMockMvc()
        try {
            val result =
                mockMvc
                    .perform(
                        get("/api/v1/notifications/subscribe")
                            .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
                    ).andExpect(request().asyncStarted())
                    .andReturn()

            // emitter 를 complete 시켜 async 처리를 끝낸다 → 컨테이너로 ASYNC 재디스패치가 트리거된다.
            registry.connectionsOf(userId).forEach { it.emitter.complete() }

            // ASYNC 디스패치가 보안 필터를 다시 타도 AuthorizationFilter 에서 Access Denied 로 떨어지지 않고
            // 정상 종료돼야 한다(SecurityConfig 의 dispatcherTypeMatchers(ASYNC).permitAll()). 이게 빠지면
            // "response is already committed" DispatcherServlet ERROR 로그가 폭증한다.
            mockMvc
                .perform(asyncDispatch(result))
                .andExpect(status().isOk)
        } finally {
            registry.removeAll(userId)
        }
    }

    @Test
    fun `인증 유저가 구독하면 SSE 스트림이 시작되고 레지스트리에 emitter 가 등록된다`() {
        val userId = UUID.randomUUID()
        try {
            buildMockMvc()
                .perform(
                    get("/api/v1/notifications/subscribe")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
                ).andExpect(request().asyncStarted())

            assertEquals(1, registry.connectionsOf(userId).size)
        } finally {
            registry.removeAll(userId)
        }
    }

    @Test
    fun `SseNotificationChannel 은 dispatcher 의 채널 리스트에 합류한다`() {
        assertTrue(channels.any { it is SseNotificationChannel })
    }

    @Test
    fun `채널로 보내면 그 유저의 등록된 emitter 가 notification 이벤트 payload 를 받는다`() {
        val userId = UUID.randomUUID()
        val emitter = RecordingSseEmitter()
        registry.register(userId, emitter)
        val notification =
            notificationRepository.save(
                Notification(
                    userId = userId,
                    type = NotificationType.TOURNAMENT_ITEM_ADDED,
                    title = "새 아이템",
                    body = "나이키 에어맥스가 추가됐어요",
                    refId = 42L,
                ),
            )

        try {
            sseNotificationChannel.send(userId, notification)

            val payloads = emitter.sentData.filterIsInstance<NotificationSsePayload>()
            assertEquals(1, payloads.size)
            val payload = payloads.first()
            assertEquals(notification.getId(), payload.id)
            assertEquals(NotificationType.TOURNAMENT_ITEM_ADDED, payload.type)
            assertEquals(42L, payload.refId)
            assertEquals("새 아이템", payload.title)
            assertEquals("나이키 에어맥스가 추가됐어요", payload.body)
            // SSE 이벤트 name 이 notification 으로 실린다.
            assertTrue(emitter.sentData.any { it is String && it.contains("event:notification") })
        } finally {
            registry.removeAll(userId)
        }
    }

    @Test
    fun `채널 전달은 수신자 본인의 emitter 에만 가고 다른 유저에게는 가지 않는다`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val otherEmitter = RecordingSseEmitter()
        registry.register(otherUserId, otherEmitter)
        val notification =
            notificationRepository.save(
                Notification(userId, NotificationType.ITEM_PARSING_COMPLETED, "제목", "본문", 1L),
            )

        try {
            sseNotificationChannel.send(userId, notification)

            assertTrue(otherEmitter.sentData.none { it is NotificationSsePayload })
        } finally {
            registry.removeAll(otherUserId)
        }
    }

    @Test
    fun `write 가 실패하는 죽은 emitter 는 전달 시 레지스트리에서 정리된다`() {
        val userId = UUID.randomUUID()
        // 이미 complete 된 emitter 는 send 시 IllegalStateException 을 던져 "죽은 연결" 을 시뮬레이션한다.
        val dead = SseEmitter().apply { complete() }
        registry.register(userId, dead)
        val notification =
            notificationRepository.save(
                Notification(userId, NotificationType.ITEM_PARSING_FAILED, "제목", "본문", 1L),
            )

        sseNotificationChannel.send(userId, notification)

        assertTrue(registry.connectionsOf(userId).isEmpty())
    }

    @Test
    fun `토너먼트 파싱 알림은 채널 payload 에 kind·tournamentId·tournamentItemId 가 실린다`() {
        val userId = UUID.randomUUID()
        val emitter = RecordingSseEmitter()
        registry.register(userId, emitter)
        val notification =
            notificationRepository.save(
                Notification(
                    userId,
                    NotificationType.ITEM_PARSING_COMPLETED,
                    "상품 정보가 저장됐어요",
                    "",
                    11L,
                    NotificationRouting.Tournament(tournamentId = 99L, tournamentItemId = 555L),
                ),
            )

        try {
            sseNotificationChannel.send(userId, notification)

            val payload = emitter.sentData.filterIsInstance<NotificationSsePayload.TournamentRouted>().first()
            assertEquals(NotificationKind.TOURNAMENT, payload.kind)
            assertEquals(99L, payload.tournamentId)
            assertEquals(555L, payload.tournamentItemId)
            assertEquals(11L, payload.refId)
        } finally {
            registry.removeAll(userId)
        }
    }

    // 토너먼트 좌표와 같은 결로 위시 딥링크 키(#933)도 실제 와이어까지 확인한다 — payload 필드만 보면
    // 직렬화 단계가 wishId 를 흘려도 통과하고, 클라는 그 사실을 모른 채 refId 역추적으로 떨어진다.
    @Test
    fun `위시 파싱 알림은 kind=WISH 이고 prod 직렬화로 wishId 가 실린다`() {
        val userId = UUID.randomUUID()
        val notification =
            notificationRepository.save(
                Notification(
                    userId,
                    NotificationType.ITEM_PARSING_COMPLETED,
                    "상품 정보가 저장됐어요",
                    "",
                    11L,
                    NotificationRouting.Wish(777L),
                ),
            )

        val payload = NotificationSsePayload.from(notification)
        val wish = assertIs<NotificationSsePayload.WishParsing>(payload)
        assertEquals(NotificationKind.WISH, wish.kind)
        assertEquals(11L, wish.refId)
        assertEquals(777L, wish.wishId)

        val node = objectMapper.readTree(objectMapper.writeValueAsString(payload))
        assertEquals("WISH", node.get("kind").asString())
        assertEquals(777L, node.get("wishId").asLong())
        // 위시 셰입엔 토너먼트 좌표가 아예 없다 — kind 만 보고 좌표를 단정하지 못하게 하는 계약(#408).
        assertFalse(node.has("tournamentId"))
        assertFalse(node.has("tournamentItemId"))
    }

    // 컬럼 도입(#933) 이전에 발송된 과거 알림 — SSE·히스토리 JSON 은 wishId 키를 남기고 값만 null 로 내린다.
    // FCM data 는 Map<String,String> 이라 같은 상황에서 키 자체를 생략한다(FirebaseMessageSenderTest 가 그쪽을 고정).
    // 두 채널의 모양이 다르므로 양쪽 다 못 박아 둔다 — 클라는 "키 없음"과 "값 null" 을 모두 폴백으로 처리해야 한다.
    @Test
    fun `wishId 없는 과거 위시 알림은 와이어에 null 로 실린다`() {
        val userId = UUID.randomUUID()
        val notification =
            notificationRepository.save(
                Notification(
                    userId,
                    NotificationType.ITEM_PARSING_COMPLETED,
                    "상품 정보가 저장됐어요",
                    "",
                    11L,
                    NotificationRouting.Wish(null),
                ),
            )

        val node = objectMapper.readTree(objectMapper.writeValueAsString(NotificationSsePayload.from(notification)))

        assertEquals("WISH", node.get("kind").asString())
        assertTrue(node.has("wishId"))
        assertTrue(node.get("wishId").isNull)
    }

    @Test
    fun `토너먼트 파싱 알림은 DB 재조회 후에도 라우팅이 보존되고 prod 직렬화로 식별자가 실린다`() {
        val userId = UUID.randomUUID()
        val saved =
            notificationRepository.save(
                Notification(
                    userId,
                    NotificationType.ITEM_PARSING_COMPLETED,
                    "상품 정보가 저장됐어요",
                    "",
                    11L,
                    NotificationRouting.Tournament(tournamentId = 99L, tournamentItemId = 555L),
                ),
            )
        // DB 에서 noarg 하이드레이션으로 재조회 — @Column/@Enumerated 매핑 + routing() 복원이 인메모리 인스턴스가 아니라 실제 왕복에서도 맞는지 검증.
        entityManager.flush()
        entityManager.clear()
        val reloaded = notificationJpaRepository.findById(saved.getId()).orElseThrow()

        val payload = NotificationSsePayload.from(reloaded)
        val tournament = assertIs<NotificationSsePayload.TournamentRouted>(payload)
        assertEquals(NotificationKind.TOURNAMENT, tournament.kind)
        assertEquals(99L, tournament.tournamentId)
        assertEquals(555L, tournament.tournamentItemId)

        // prod 가 쓰는 Spring 관리 Jackson(tools.jackson)으로 직렬화해 실제 SSE 와이어 셰입을 검증한다.
        // 문자열 contains 대신 트리 경로/타입으로 단언해 공백·필드순서 변화엔 안 깨지고 구조 변경은 잡는다.
        val node = objectMapper.readTree(objectMapper.writeValueAsString(payload))
        assertEquals("TOURNAMENT", node.get("kind").asString())
        assertEquals(99L, node.get("tournamentId").asLong())
        assertEquals(555L, node.get("tournamentItemId").asLong())
        // 폐기된 category·imageUrl 은 와이어에서 사라졌다 — kind 가 그 자리를 대신한다(#473 고도화).
        assertFalse(node.has("category"))
        assertFalse(node.has("imageUrl"))
        // Jackson3+kotlin module 은 isRead 필드명으로 직렬화한다(모듈 없는 Jackson2 였으면 "read" 라 이 키가 없다).
        assertTrue(node.has("isRead"))
    }

    @Test
    fun `connect 이벤트 data 로 받은 연결 번호로 하트비트를 보내면 200 이다`() {
        val userId = UUID.randomUUID()
        val mockMvc = buildMockMvc()
        try {
            val result =
                mockMvc
                    .perform(
                        get("/api/v1/notifications/subscribe")
                            .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
                    ).andExpect(request().asyncStarted())
                    .andReturn()
            val connectionId = connectionIdOf(result.response.contentAsString)

            mockMvc
                .perform(
                    post("/api/v1/notifications/heartbeat")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"connectionId":"$connectionId"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.data").doesNotExist())
        } finally {
            registry.removeAll(userId)
        }
    }

    @Test
    fun `서버에 없는 연결 번호로 하트비트를 보내면 409 NOTIFICATION-002 다`() {
        val userId = UUID.randomUUID()

        buildMockMvc()
            .perform(
                post("/api/v1/notifications/heartbeat")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"connectionId":"${UUID.randomUUID()}"}"""),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value(NotificationErrorCode.UNKNOWN_CONNECTION.code))
            .andExpect(jsonPath("$.detail").value(NotificationErrorCode.UNKNOWN_CONNECTION.message))
    }

    @Test
    fun `다른 유저의 연결 번호로 하트비트를 보내면 모르는 연결과 같이 409 다`() {
        val owner = UUID.randomUUID()
        val other = UUID.randomUUID()
        val emitter = RecordingSseEmitter()
        val connection = registry.register(owner, emitter)
        try {
            buildMockMvc()
                .perform(
                    post("/api/v1/notifications/heartbeat")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"connectionId":"${connection.id}"}"""),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value(NotificationErrorCode.UNKNOWN_CONNECTION.code))
        } finally {
            registry.removeAll(owner)
        }
    }

    @Test
    fun `connectionId 없이 하트비트를 보내면 400 이고 detail 은 category 고정 문구다`() {
        buildMockMvc()
            .perform(
                post("/api/v1/notifications/heartbeat")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(UUID.randomUUID()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(ErrorCategory.INVALID_INPUT.description))
    }

    @Test
    fun `토큰 없이 하트비트를 보내면 401 이다`() {
        buildMockMvc()
            .perform(
                post("/api/v1/notifications/heartbeat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"connectionId":"${UUID.randomUUID()}"}"""),
            ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `서버 ping 은 heartbeat 이벤트에 그 연결의 번호를 실어 보낸다`() {
        val userId = UUID.randomUUID()
        val emitter = RecordingSseEmitter()
        val connection = registry.register(userId, emitter)
        try {
            localDelivery.ping()

            assertTrue(emitter.sentData.any { it is String && it.contains("event:heartbeat") })
            assertTrue(emitter.sentData.contains(connection.id.toString()))
        } finally {
            registry.removeAll(userId)
        }
    }

    @Test
    fun `하트비트를 보내다 임계값 넘게 끊긴 연결만 서버가 닫고 한 번도 안 보낸 연결은 건드리지 않는다`() {
        val userId = UUID.randomUUID()
        val now = Instant.parse("2026-09-08T00:00:00Z")
        val stale = RecordingSseEmitter()
        val alive = RecordingSseEmitter()
        val legacy = RecordingSseEmitter()
        val staleConnection = registry.register(userId, stale)
        val aliveConnection = registry.register(userId, alive)
        registry.register(userId, legacy)
        registry.touch(userId, staleConnection.id, now)
        registry.touch(userId, aliveConnection.id, now.plusSeconds(50))
        try {
            val evicted = localDelivery.evictStale(now.plusSeconds(61), Duration.ofSeconds(60))

            assertEquals(1, evicted)
            assertEquals(listOf<SseEmitter>(alive, legacy), registry.connectionsOf(userId).map { it.emitter })
            assertTrue(stale.completed)
            assertFalse(alive.completed)
            assertFalse(legacy.completed)
        } finally {
            registry.removeAll(userId)
        }
    }

    // MockMvc 응답 버퍼에 남은 SSE 와이어("event:connect\ndata:<uuid>")에서 연결 번호를 뽑는다.
    private fun connectionIdOf(stream: String): UUID {
        val match =
            requireNotNull(
                Regex("event:connect\\s*\\ndata:([0-9a-f-]{36})").find(stream),
            ) { "connect 이벤트가 응답에 없다: $stream" }
        return UUID.fromString(match.groupValues[1])
    }
}

// send(SseEventBuilder) 를 가로채 실제 IO 없이 전송 내용을 기록한다. build() 가 내놓는 data 항목
// (메타 라인 문자열 + payload 객체)을 그대로 모아, 테스트가 payload 와 이벤트 name 을 단언할 수 있게 한다.
private class RecordingSseEmitter : SseEmitter() {
    val sentData = CopyOnWriteArrayList<Any>()

    @Volatile
    var completed = false

    override fun send(builder: SseEmitter.SseEventBuilder) {
        builder.build().forEach { sentData.add(it.data) }
    }

    override fun complete() {
        completed = true
        super.complete()
    }
}
