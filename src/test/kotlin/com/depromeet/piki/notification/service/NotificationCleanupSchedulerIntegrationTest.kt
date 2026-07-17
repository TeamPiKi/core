package com.depromeet.piki.notification.service

import com.depromeet.piki.notification.config.NotificationProperties
import com.depromeet.piki.notification.domain.Notification
import com.depromeet.piki.notification.domain.NotificationType
import com.depromeet.piki.notification.fcm.domain.UserDevice
import com.depromeet.piki.notification.fcm.repository.UserDeviceRepository
import com.depromeet.piki.notification.repository.NotificationRepository
import com.depromeet.piki.notification.sse.SseEmitterRegistry
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.RecordingSseEmitter
import com.depromeet.piki.support.StubFcmMessageSender
import com.depromeet.piki.support.uuidToBytes
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// N일 자동삭제 스케줄러의 배지 fan-out wiring 검증 — 자동삭제로 안읽음이 사라진 유저에게 SSE·FCM 배지가 실제로
// 전파되고, 읽음만 지워진 유저에겐 전파되지 않는지 확인한다. 배지 동기화는 @Async 라 응답 경로 밖 워커에서 돌아
// @Transactional 자동 롤백으로는 워커가 미커밋 데이터를 못 본다 — 실제 커밋하고 Awaitility 로 발송 완료를 기다린다
// (NotificationBadgeSyncAsyncIntegrationTest 와 동일 결). 격리 userId 로 만든 행은 메서드 끝에서 jdbcTemplate 으로 정리한다.
class NotificationCleanupSchedulerIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var scheduler: NotificationCleanupScheduler

    @Autowired private lateinit var registry: SseEmitterRegistry

    @Autowired private lateinit var userDeviceRepository: UserDeviceRepository

    @Autowired private lateinit var notificationRepository: NotificationRepository

    @Autowired private lateinit var stubFcmMessageSender: StubFcmMessageSender

    @Autowired private lateinit var notificationProperties: NotificationProperties

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var objectMapper: ObjectMapper

    private fun saveUnread(userId: UUID): Long =
        notificationRepository
            .save(Notification(userId, NotificationType.ITEM_PARSING_COMPLETED, "제목", "본문", 11L))
            .getId()

    // created_at 은 @CreatedDate·updatable=false 라 엔티티로 못 바꾼다. is_read 도 함께 native SQL 로 심는다.
    private fun age(
        id: Long,
        at: LocalDateTime,
        read: Boolean = false,
    ) {
        jdbcTemplate.update("UPDATE notifications SET created_at = ?, is_read = ? WHERE id = ?", Timestamp.valueOf(at), read, id)
    }

    // @Transactional 없는 테스트라 @Modifying 삭제(트랜잭션 필요)는 못 쓴다 — jdbcTemplate 으로 직접 정리한다.
    private fun cleanupRows(vararg userIds: UUID) {
        userIds.forEach {
            jdbcTemplate.update("DELETE FROM user_devices WHERE user_id = ?", uuidToBytes(it))
            jdbcTemplate.update("DELETE FROM notifications WHERE user_id = ?", uuidToBytes(it))
        }
    }

    @Test
    fun `자동삭제는 안읽음이 지워진 유저에게만 SSE·FCM 배지를 전파하고 읽음만 지워진 유저는 건드리지 않는다`() {
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        val tokenA = "tokenA-$userA"
        val tokenB = "tokenB-$userB"
        try {
            // 보존기간(cutoff = now - retentionDays)보다 확실히 오래된 시각.
            val old = LocalDateTime.now().minusDays(notificationProperties.retentionDays + 10)

            // userA: 오래된 안읽음(삭제 대상·배지 영향) + 최근 안읽음(남음 → 삭제 후 안읽음 1건).
            val aOldUnread = saveUnread(userA)
            saveUnread(userA)
            age(aOldUnread, old, read = false)

            // userB: 오래된 읽음(삭제되지만 안읽음 불변이라 배지 동기화 대상 아님).
            val bOldRead = saveUnread(userB)
            age(bOldRead, old, read = true)

            userDeviceRepository.save(UserDevice(userId = userA, deviceId = "dA", fcmToken = tokenA))
            userDeviceRepository.save(UserDevice(userId = userB, deviceId = "dB", fcmToken = tokenB))

            val emitterA = RecordingSseEmitter().also { registry.register(userA, it) }
            val emitterB = RecordingSseEmitter().also { registry.register(userB, it) }
            val fcmCalls = CopyOnWriteArrayList<Pair<List<String>, Int>>()
            stubFcmMessageSender.onSendBadgeSync = { tokens, badge ->
                fcmCalls.add(tokens to badge)
                emptyList()
            }

            try {
                scheduler.cleanup()

                await().atMost(Duration.ofSeconds(5)).untilAsserted {
                    // userA: 온라인(SSE) 기기에 갱신 안읽음 수(1) 전파 — payload 는 와이어 JSON 그대로 단언한다.
                    val payload = emitterA.payloadsOf("silent-sync").singleOrNull()?.let(objectMapper::readTree)
                    assertEquals(1L, payload?.get("unreadCount")?.asLong())
                    // userA: 오프라인(FCM) 기기에 badge=1 전파.
                    assertEquals(1, fcmCalls.singleOrNull { it.first.contains(tokenA) }?.second)
                    // userB: 읽음만 지워져 배지 불변 → SSE·FCM 어느 쪽도 호출되지 않는다.
                    assertTrue(emitterB.sends.isEmpty())
                    assertTrue(fcmCalls.none { it.first.contains(tokenB) })
                }
            } finally {
                registry.unregister(userA, emitterA)
                registry.unregister(userB, emitterB)
            }
        } finally {
            cleanupRows(userA, userB)
        }
    }
}
