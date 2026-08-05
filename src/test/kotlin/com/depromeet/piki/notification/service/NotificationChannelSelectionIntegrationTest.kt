package com.depromeet.piki.notification.service

import com.depromeet.piki.notification.fcm.domain.UserDevice
import com.depromeet.piki.notification.fcm.repository.UserDeviceRepository
import com.depromeet.piki.notification.repository.NotificationRepository
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubFcmMessageSender
import com.depromeet.piki.tournament.domain.TournamentUser
import com.depromeet.piki.tournament.event.TournamentItemAdded
import com.depromeet.piki.tournament.event.TournamentJoined
import com.depromeet.piki.tournament.repository.TournamentUserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// 디스패처의 타입별 채널 선택(notification_templates.push_enabled)을 실제 빈으로 검증한다.
// 같은 수신자(FCM 토큰 보유)에 대해 푸시 비대상(아이템 추가 — 시드 기본 push off)은 FCM 을 안 타고 푸시 대상(참여 — push on)은 타는지로 채널 선택을 가른다.
// 외부 FCM 만 StubFcmMessageSender 로 격리하고, 수신자·토큰·알림 영속은 실제 DB(Testcontainers)다.
@Transactional
class NotificationChannelSelectionIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var notificationDispatcher: NotificationDispatcher

    @Autowired private lateinit var tournamentUserRepository: TournamentUserRepository

    @Autowired private lateinit var userDeviceRepository: UserDeviceRepository

    @Autowired private lateinit var notificationRepository: NotificationRepository

    @Autowired private lateinit var stubFcmMessageSender: StubFcmMessageSender

    @Test
    fun `푸시 비대상 알림(아이템 추가)은 FCM 토큰이 있어도 푸시를 보내지 않는다`() {
        val tournamentId = 4001L
        val actor = UUID.randomUUID()
        val recipient = UUID.randomUUID()
        listOf(actor, recipient).forEach { tournamentUserRepository.save(TournamentUser(tournamentId, it)) }
        userDeviceRepository.save(UserDevice(userId = recipient, deviceId = "d1", fcmToken = "token-1"))
        var pushed = false
        stubFcmMessageSender.onSend = { _, _, _ ->
            pushed = true
            emptyList()
        }

        notificationDispatcher.dispatch(TournamentItemAdded(tournamentId, actor))

        // 알림은 영속돼 알림센터엔 남지만(채널 무관), FCM 은 타입 정책상 빠진다.
        assertEquals(1, notificationRepository.findPage(recipient, cursor = null, limit = 10).size)
        assertFalse(pushed)
    }

    @Test
    fun `푸시 대상 알림(토너먼트 참여)은 FCM 토큰이 있으면 푸시를 보낸다`() {
        val tournamentId = 4002L
        val actor = UUID.randomUUID()
        val recipient = UUID.randomUUID()
        listOf(actor, recipient).forEach { tournamentUserRepository.save(TournamentUser(tournamentId, it)) }
        userDeviceRepository.save(UserDevice(userId = recipient, deviceId = "d1", fcmToken = "token-1"))
        var pushedTokens: List<String>? = null
        stubFcmMessageSender.onSend = { tokens, _, _ ->
            pushedTokens = tokens
            emptyList()
        }

        notificationDispatcher.dispatch(TournamentJoined(tournamentId, actor))

        assertEquals(listOf("token-1"), pushedTokens)
    }
}
