package com.depromeet.piki.notification.service

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.notification.controller.dto.UnreadCountChanged
import com.depromeet.piki.notification.domain.Notification
import com.depromeet.piki.notification.domain.NotificationType
import com.depromeet.piki.notification.fcm.domain.UserDevice
import com.depromeet.piki.notification.fcm.repository.UserDeviceRepository
import com.depromeet.piki.notification.repository.NotificationRepository
import com.depromeet.piki.notification.sse.SseEmitterRegistry
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubFcmMessageSender
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNull

// 읽음 후 silent badge 동기화(#487)는 PushNotificationChannel.syncBadge(@Async notificationExecutor)로 응답 경로에서
// 분리돼 별도 워커 스레드·새 트랜잭션에서 돈다. 그래서 @Transactional 자동 롤백 패턴으로는 워커가 미커밋 데이터를
// 못 봐 검증할 수 없다 — 여기서는 @Transactional 없이 실제 커밋하고 Awaitility 로 비동기 발송 완료를 기다린다.
// (CLAUDE.md '동시성·시간 의존 통합 테스트' 별도 분류.) 자기가 만든 행은 격리 userId 로 메서드 끝에서 직접 정리한다.
// 캡처 변수는 워커 스레드가 쓰고 테스트 스레드가 읽으므로 가시성 보장을 위해 Atomic 으로 둔다.
class NotificationBadgeSyncAsyncIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var pushNotificationChannel: PushNotificationChannel

    @Autowired private lateinit var registry: SseEmitterRegistry

    @Autowired private lateinit var userDeviceRepository: UserDeviceRepository

    @Autowired private lateinit var notificationRepository: NotificationRepository

    @Autowired private lateinit var stubFcmMessageSender: StubFcmMessageSender

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    private fun authHeader(userId: UUID): String = "Bearer ${jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)}"

    private fun buildMockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun saveNotification(userId: UUID): Long =
        notificationRepository
            .save(Notification(userId, NotificationType.ITEM_PARSING_COMPLETED, "제목", "본문", 11L))
            .getId()

    // @Transactional 없는 테스트라 @Modifying 리포지토리 삭제(트랜잭션 필요)는 못 쓴다 — jdbcTemplate 으로 직접 정리한다.
    private fun cleanup(userId: UUID) {
        jdbcTemplate.update("DELETE FROM user_devices WHERE user_id = ?", uuidToBytes(userId))
        jdbcTemplate.update("DELETE FROM notifications WHERE user_id = ?", uuidToBytes(userId))
    }

    @Test
    fun `syncBadge 는 그 유저의 모든 기기로 silent 발송하며 갱신 badge 를 전달한다`() {
        val userId = UUID.randomUUID()
        val token1 = "token-1-$userId"
        val token2 = "token-2-$userId"
        try {
            userDeviceRepository.save(UserDevice(userId = userId, deviceId = "device-1", fcmToken = token1))
            userDeviceRepository.save(UserDevice(userId = userId, deviceId = "device-2", fcmToken = token2))
            val capturedTokens = AtomicReference<List<String>>()
            val capturedBadge = AtomicInteger(-1)
            stubFcmMessageSender.onSendBadgeSync = { tokens, badge ->
                capturedTokens.set(tokens)
                capturedBadge.set(badge)
                emptyList()
            }

            pushNotificationChannel.syncBadge(userId, 5)

            await().atMost(Duration.ofSeconds(5)).untilAsserted {
                assertEquals(setOf(token1, token2), capturedTokens.get()?.toSet())
                assertEquals(5, capturedBadge.get())
            }
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `syncBadge 도 죽은 토큰을 user_devices 에서 정리한다`() {
        val userId = UUID.randomUUID()
        val live = "live-$userId"
        val dead = "dead-$userId"
        try {
            userDeviceRepository.save(UserDevice(userId = userId, deviceId = "device-1", fcmToken = live))
            userDeviceRepository.save(UserDevice(userId = userId, deviceId = "device-2", fcmToken = dead))
            stubFcmMessageSender.onSendBadgeSync = { _, _ -> listOf(dead) }

            pushNotificationChannel.syncBadge(userId, 0)

            await().atMost(Duration.ofSeconds(5)).untilAsserted {
                assertEquals(listOf(live), userDeviceRepository.findAllByUserId(userId).map { it.fcmToken })
            }
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `read 처리 후 그 유저의 기기로 갱신된 안읽음 수를 silent push 한다`() {
        val userId = UUID.randomUUID()
        val token = "token-$userId"
        try {
            userDeviceRepository.save(UserDevice(userId = userId, deviceId = "device-1", fcmToken = token))
            val target = saveNotification(userId)
            saveNotification(userId) // 읽지 않을 1건 → read 후 남는 안읽음 = 1
            val capturedTokens = AtomicReference<List<String>>()
            val capturedBadge = AtomicInteger(-1)
            stubFcmMessageSender.onSendBadgeSync = { tokens, badge ->
                capturedTokens.set(tokens)
                capturedBadge.set(badge)
                emptyList()
            }

            buildMockMvc()
                .perform(
                    post("/api/v1/notifications/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"ids":[$target]}""")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.data.unreadCount").value(1))

            // 멀티 디바이스 동기화 — 읽은 기기 외 다른 기기가 badge 를 맞추도록 갱신 안읽음 수(1)를 그 유저 토큰으로 silent push.
            await().atMost(Duration.ofSeconds(5)).untilAsserted {
                assertEquals(listOf(token), capturedTokens.get())
                assertEquals(1, capturedBadge.get())
            }
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `read 처리 후 온라인(SSE) 기기에 silent-sync(UNREAD_COUNT_CHANGED)로 갱신 안읽음 수를 보낸다`() {
        val userId = UUID.randomUUID()
        try {
            val target = saveNotification(userId)
            saveNotification(userId) // 안 읽을 1건 → 읽음 후 안읽음 = 1
            val emitter = BadgeRecordingEmitter().also { registry.register(userId, it) }
            try {
                buildMockMvc()
                    .perform(
                        post("/api/v1/notifications/read")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"ids":[$target]}""")
                            .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
                    ).andExpect(status().isOk)

                // badge SSE 는 SilentSyncDispatcher.dispatch(@Async)로 응답 경로 밖에서 발행된다. Awaitility 로 도착을 기다린다.
                await().atMost(Duration.ofSeconds(5)).untilAsserted {
                    val payload = emitter.payloads().single()
                    assertEquals(1, payload.unreadCount)
                    // wire 직렬화 contract — type 판별자 + 안읽음 수가 실제 JSON 에 실리는지(클라가 type 으로 분기).
                    val node = objectMapper.readTree(objectMapper.writeValueAsString(payload))
                    assertEquals("UNREAD_COUNT_CHANGED", node.get("type").asString())
                    assertEquals(1L, node.get("unreadCount").asLong())
                    // 옛 카테고리 맵은 SSE 페이로드에서도 사라졌다 — 응답 DTO 쪽만 잠그면 이 경로로 되살아나도 안 걸린다.
                    assertNull(node.get("unreadCountByCategory"))
                }
            } finally {
                registry.unregister(userId, emitter)
            }
        } finally {
            cleanup(userId)
        }
    }
}

// send 를 가로채 실제 IO 없이 전송된 silent-sync payload 를 기록한다(TournamentItemParsedSseIntegrationTest 와 동일 패턴).
private class BadgeRecordingEmitter : SseEmitter() {
    val sentData = CopyOnWriteArrayList<Any>()

    override fun send(builder: SseEmitter.SseEventBuilder) {
        builder.build().forEach { sentData.add(it.data) }
    }

    fun payloads(): List<UnreadCountChanged> = sentData.filterIsInstance<UnreadCountChanged>()
}
