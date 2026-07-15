package com.depromeet.piki.notification.sse

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.notification.controller.dto.NotificationSsePayload
import com.depromeet.piki.notification.controller.dto.UnreadCountChanged
import com.depromeet.piki.notification.domain.Notification
import com.depromeet.piki.notification.domain.NotificationCategory
import com.depromeet.piki.notification.domain.NotificationKind
import com.depromeet.piki.notification.domain.NotificationRouting
import com.depromeet.piki.notification.domain.NotificationType
import com.depromeet.piki.notification.repository.NotificationJpaRepository
import com.depromeet.piki.notification.repository.NotificationRepository
import com.depromeet.piki.notification.service.NotificationChannel
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.user.domain.IdentityType
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import jakarta.persistence.EntityManager
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// 시스템 알림(actor 없음)의 imageUrl 을 채우는 기본 아바타 — 운영에선 DefaultPushImage 가 publicBaseUrl 로 조립한다.
private const val DEFAULT_PUSH_IMAGE_URL = "https://img.test/defaults/push-icon.svg"

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

    @Autowired private lateinit var localSseDelivery: LocalSseDelivery

    @Autowired private lateinit var channels: List<NotificationChannel>

    @Autowired private lateinit var notificationRepository: NotificationRepository

    @Autowired private lateinit var notificationJpaRepository: NotificationJpaRepository

    @Autowired private lateinit var entityManager: EntityManager

    @Autowired private lateinit var objectMapper: ObjectMapper

    private fun authHeader(userId: UUID): String = "Bearer ${jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)}"

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
            registry.emittersOf(userId).toList().forEach { it.complete() }

            // ASYNC 디스패치가 보안 필터를 다시 타도 AuthorizationFilter 에서 Access Denied 로 떨어지지 않고
            // 정상 종료돼야 한다(SecurityConfig 의 dispatcherTypeMatchers(ASYNC).permitAll()). 이게 빠지면
            // "response is already committed" DispatcherServlet ERROR 로그가 폭증한다.
            mockMvc
                .perform(asyncDispatch(result))
                .andExpect(status().isOk)
        } finally {
            registry.emittersOf(userId).toList().forEach { registry.unregister(userId, it) }
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

            assertEquals(1, registry.emittersOf(userId).size)
        } finally {
            registry.emittersOf(userId).toList().forEach { registry.unregister(userId, it) }
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
            // SSE id 필드에 알림 id 가 실린다 — 클라이언트 재연결(Last-Event-ID) 복구의 기준점.
            assertTrue(emitter.sentData.any { it is String && it.contains("id:${notification.getId()}\n") })
        } finally {
            registry.unregister(userId, emitter)
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
            registry.unregister(otherUserId, otherEmitter)
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

        assertTrue(registry.emittersOf(userId).isEmpty())
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
            registry.unregister(userId, emitter)
        }
    }

    @Test
    fun `위시 파싱 알림 payload 는 kind=WISH 이고 토너먼트 식별자가 비어 있다`() {
        val userId = UUID.randomUUID()
        val notification =
            notificationRepository.save(
                Notification(
                    userId,
                    NotificationType.ITEM_PARSING_COMPLETED,
                    "상품 정보가 저장됐어요",
                    "",
                    11L,
                    NotificationRouting.Wish,
                ),
            )

        val payload = NotificationSsePayload.from(notification, DEFAULT_PUSH_IMAGE_URL)
        val wish = assertIs<NotificationSsePayload.WishParsing>(payload)
        assertEquals(NotificationKind.WISH, wish.kind)
        assertEquals(11L, wish.refId)
        // 시스템 알림(파싱·actor 없음) → category=SYSTEM, imageUrl 은 defaultPushImg 로 채워진다.
        assertEquals(NotificationCategory.SYSTEM, wish.category)
        assertEquals(DEFAULT_PUSH_IMAGE_URL, wish.imageUrl)
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

        val payload = NotificationSsePayload.from(reloaded, DEFAULT_PUSH_IMAGE_URL)
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
        // imageUrl·category 도 와이어에 실린다 — 시스템 알림이라 category=SYSTEM, imageUrl=defaultPushImg.
        assertEquals("SYSTEM", node.get("category").asString())
        assertEquals(DEFAULT_PUSH_IMAGE_URL, node.get("imageUrl").asString())
        // Jackson3+kotlin module 은 isRead 필드명으로 직렬화한다(모듈 없는 Jackson2 였으면 "read" 라 이 키가 없다).
        assertTrue(node.has("isRead"))
    }

    // --- Last-Event-ID 재연결 replay (#750) ---
    // replay 는 구독 시점에 컨트롤러 스레드에서 동기로 일어나므로, asyncStarted 상태의 응답 버퍼(contentAsString)로
    // 실제 와이어(text/event-stream)에 흐른 이벤트를 그대로 단언한다. SSE id 필드 라인은 "id:{알림id}\n" 형식이라
    // data JSON 의 "id":{알림id} 와 substring 이 겹치지 않는다.

    @Test
    fun `Last-Event-ID 를 실어 재연결하면 놓친 알림만 발생 순서대로 replay 된다`() {
        val userId = UUID.randomUUID()
        val received =
            notificationRepository.save(Notification(userId, NotificationType.TOURNAMENT_JOINED, "이미 받은 알림", "", 1L))
        val missedFirst =
            notificationRepository.save(Notification(userId, NotificationType.TOURNAMENT_ITEM_ADDED, "놓친 알림 1", "", 2L))
        val missedSecond =
            notificationRepository.save(Notification(userId, NotificationType.TOURNAMENT_STARTED, "놓친 알림 2", "", 3L))

        try {
            val content =
                buildMockMvc()
                    .perform(
                        get("/api/v1/notifications/subscribe")
                            .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                            .header("Last-Event-ID", received.getId().toString()),
                    ).andExpect(request().asyncStarted())
                    .andReturn()
                    .response.contentAsString

            // 놓친 두 건만 replay 된다 — 이미 받은 알림(Last-Event-ID 그 자체)은 다시 오지 않는다.
            assertEquals(2, "event:notification".toRegex().findAll(content).count())
            assertFalse(content.contains("id:${received.getId()}\n"))
            // 발생 순서(오래된 것부터, id asc)대로 흐른다.
            val firstIndex = content.indexOf("id:${missedFirst.getId()}\n")
            val secondIndex = content.indexOf("id:${missedSecond.getId()}\n")
            assertTrue(firstIndex >= 0 && secondIndex >= 0 && firstIndex < secondIndex)
        } finally {
            registry.emittersOf(userId).toList().forEach { registry.unregister(userId, it) }
        }
    }

    @Test
    fun `Last-Event-ID 이후 알림이 없으면 replay 없이 connect 만 온다`() {
        val userId = UUID.randomUUID()
        val latest =
            notificationRepository.save(Notification(userId, NotificationType.TOURNAMENT_JOINED, "마지막 알림", "", 1L))

        try {
            val content =
                buildMockMvc()
                    .perform(
                        get("/api/v1/notifications/subscribe")
                            .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                            .header("Last-Event-ID", latest.getId().toString()),
                    ).andExpect(request().asyncStarted())
                    .andReturn()
                    .response.contentAsString

            assertTrue(content.contains("event:connect"))
            assertFalse(content.contains("event:notification"))
        } finally {
            registry.emittersOf(userId).toList().forEach { registry.unregister(userId, it) }
        }
    }

    @Test
    fun `숫자가 아닌 Last-Event-ID 는 첫 연결로 취급돼 replay 없이 정상 구독된다`() {
        val userId = UUID.randomUUID()
        notificationRepository.save(Notification(userId, NotificationType.TOURNAMENT_JOINED, "기존 알림", "", 1L))

        try {
            val content =
                buildMockMvc()
                    .perform(
                        get("/api/v1/notifications/subscribe")
                            .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                            .header("Last-Event-ID", "not-a-number"),
                    ).andExpect(request().asyncStarted())
                    .andReturn()
                    .response.contentAsString

            // 재연결 루프를 400 으로 깨지 않고 무시한다 — 연결은 성립하고 replay 만 없다.
            assertTrue(content.contains("event:connect"))
            assertFalse(content.contains("event:notification"))
        } finally {
            registry.emittersOf(userId).toList().forEach { registry.unregister(userId, it) }
        }
    }

    @Test
    fun `놓친 알림이 replay 상한을 초과하면 replay 를 통째로 생략한다`() {
        val userId = UUID.randomUUID()
        val baseline =
            notificationRepository.save(Notification(userId, NotificationType.TOURNAMENT_JOINED, "기준점", "", 1L))
        repeat(SseReconnectReplayer.REPLAY_LIMIT + 1) {
            notificationRepository.save(Notification(userId, NotificationType.TOURNAMENT_ITEM_ADDED, "밀린 알림", "", 1L))
        }

        try {
            val content =
                buildMockMvc()
                    .perform(
                        get("/api/v1/notifications/subscribe")
                            .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                            .header("Last-Event-ID", baseline.getId().toString()),
                    ).andExpect(request().asyncStarted())
                    .andReturn()
                    .response.contentAsString

            // 일부만 보내면 replay 구간 뒤에 조용한 구멍이 남으므로 통째로 생략하고 목록 재조회 계약에 맡긴다.
            assertTrue(content.contains("event:connect"))
            assertFalse(content.contains("event:notification"))
        } finally {
            registry.emittersOf(userId).toList().forEach { registry.unregister(userId, it) }
        }
    }

    @Test
    fun `silent-sync 이벤트에는 SSE id 가 실리지 않는다`() {
        val userId = UUID.randomUUID()
        val emitter = RecordingSseEmitter()
        registry.register(userId, emitter)

        try {
            localSseDelivery.deliverSilentSync(
                listOf(userId),
                UnreadCountChanged.of(mapOf(NotificationCategory.ACTIVITY to 1L, NotificationCategory.SYSTEM to 0L)),
            )

            // id 없는 이벤트는 클라이언트 lastEventId 를 갱신하지 않는다 — 비영속이라 replay 불가능한 silent-sync 가
            // 재연결 복구 기준점을 오염시키지 않는 프로토콜 계약.
            assertTrue(emitter.sentData.any { it is String && it.contains("event:silent-sync") })
            assertTrue(emitter.sentData.filterIsInstance<String>().none { it.contains("id:") })
        } finally {
            registry.unregister(userId, emitter)
        }
    }
}

// send(SseEventBuilder) 를 가로채 실제 IO 없이 전송 내용을 기록한다. build() 가 내놓는 data 항목
// (메타 라인 문자열 + payload 객체)을 그대로 모아, 테스트가 payload 와 이벤트 name 을 단언할 수 있게 한다.
private class RecordingSseEmitter : SseEmitter() {
    val sentData = CopyOnWriteArrayList<Any>()

    override fun send(builder: SseEmitter.SseEventBuilder) {
        builder.build().forEach { sentData.add(it.data) }
    }
}
