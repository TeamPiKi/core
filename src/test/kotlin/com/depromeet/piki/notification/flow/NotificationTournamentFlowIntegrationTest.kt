package com.depromeet.piki.notification.flow

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.notification.fcm.service.UserDeviceService
import com.depromeet.piki.notification.repository.NotificationRepository
import com.depromeet.piki.notification.service.NotificationDispatcher
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.tournament.domain.Tournament
import com.depromeet.piki.tournament.domain.TournamentUser
import com.depromeet.piki.tournament.event.TournamentCompleted
import com.depromeet.piki.tournament.event.TournamentResultReady
import com.depromeet.piki.tournament.repository.TournamentRepository
import com.depromeet.piki.tournament.repository.TournamentUserRepository
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.repository.UserRepository
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 신규 토너먼트 알림(#473)의 전체 flow 통합 검증:
// 유저 진입(회원·게스트 생성) → FCM 토큰 등록 → 토너먼트 결과 발송(dispatch) → 수신자 히스토리 조회 → 읽음 처리.
// @Transactional 테스트는 AFTER_COMMIT 리스너가 안 뜨므로, 발송은 dispatcher 로 직접 구동한다 —
// 여기서 보는 건 디스패치 **이후** (수신자 해석 · 저장 · 히스토리 · 읽음)다.
// 그 앞 구간(이벤트 발행 → 리스너 → 디스패처)은 NotificationEventSubscriptionIntegrationTest 가 실제 발행으로 검증한다.
// 두 파일이 함께 있어야 경로가 끝까지 닫힌다 — 예전엔 앞 구간을 아무도 안 봐서 리스너가 통째로 빠진 채 초록불이었다(#961).
@Transactional
class NotificationTournamentFlowIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Autowired private lateinit var userRepository: UserRepository

    @Autowired private lateinit var userDeviceService: UserDeviceService

    @Autowired private lateinit var tournamentRepository: TournamentRepository

    @Autowired private lateinit var tournamentUserRepository: TournamentUserRepository

    @Autowired private lateinit var notificationDispatcher: NotificationDispatcher

    @Autowired private lateinit var notificationRepository: NotificationRepository

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun authHeader(userId: UUID): String = "Bearer ${jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)}"

    @Test
    fun `유저 진입 → 토큰 등록 → 토너먼트 결과 발송 → 히스토리 조회 → 읽음 까지 전체 flow`() {
        // 1) 유저 진입 — 주최자(회원) · 참여자(회원)
        val owner = saveUser("주최자", "https://img/owner.png", IdentityType.MEMBER)
        val participant = saveUser("참여자", "https://img/part.png", IdentityType.MEMBER)
        val rootId = createRootWithOwnerAndParticipant(owner, participant)

        // 2) 참여자가 앱 진입 시 FCM 토큰 등록 (게스트·회원 모두 가능 — 푸시 전달 경로 확보)
        userDeviceService.register(participant, deviceId = "device-1", fcmToken = "fcm-token-1")
        assertEquals(listOf("fcm-token-1"), userDeviceService.findTokens(participant))

        // 3) 주최자가 ROOT 를 완료 → 결과 알림 발송(참여자 − 주최자 에게). actor=주최자.
        notificationDispatcher.dispatch(TournamentResultReady(rootTournamentId = rootId, actorId = owner))

        // 4) 발송 결과 — 참여자에게 RESULT_READY 1건이 actor(주최자) 프사 snapshot 과 함께 저장된다.
        val saved = notificationRepository.findPage(participant, cursor = null, limit = 10)
        assertEquals(1, saved.size)
        assertEquals("https://img/owner.png", saved.first().actorImageUrl) // 주최자 프사 snapshot

        // 5) 참여자 히스토리 조회 (GET /notifications) — type·kind·title 이 셰입대로 내려온다.
        val mvc = mockMvc()
        mvc
            .perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, authHeader(participant)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].type").value("TOURNAMENT_RESULT_READY"))
            .andExpect(jsonPath("$.data.items[0].kind").value("TOURNAMENT"))
            .andExpect(jsonPath("$.data.items[0].title", containsString("주최자")))
            .andExpect(jsonPath("$.data.items[0].isRead").value(false))
            .andExpect(jsonPath("$.data.unreadCount").value(1))

        // 6) 참여자가 읽음 처리 (POST /read all) — 처리 후 안읽음 수 0 을 서버 권위값으로 돌려준다.
        mvc
            .perform(
                post("/api/v1/notifications/read")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"all":true}""")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(participant)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.unreadCount").value(0))
    }

    @Test
    fun `클론 완료 발송은 ROOT 주최자에게만 가고 참여자에겐 안 간다`() {
        val owner = saveUser("주최자", "https://img/owner.png", IdentityType.MEMBER)
        val member = saveUser("멤버", "https://img/member.png", IdentityType.MEMBER)
        val rootId = createRootWithOwnerAndParticipant(owner, member)

        // 멤버가 자기 클론을 완료 → ROOT 주최자에게 "멤버님이 완료했어요". (actor=멤버)
        notificationDispatcher.dispatch(TournamentCompleted(rootTournamentId = rootId, actorId = member))

        // 주최자는 1건 받고(actor=멤버 프사), 멤버 본인(actor)은 0건.
        val ownerInbox = notificationRepository.findPage(owner, cursor = null, limit = 10)
        assertEquals(1, ownerInbox.size)
        assertEquals("https://img/member.png", ownerInbox.first().actorImageUrl)
        assertTrue(notificationRepository.findPage(member, cursor = null, limit = 10).isEmpty())
    }

    private fun saveUser(
        nickname: String,
        profileImage: String,
        identityType: IdentityType,
    ): UUID {
        val id = UUID.randomUUID()
        userRepository.save(User(id = id, nickname = nickname, profileImage = profileImage, identityType = identityType))
        return id
    }

    // ROOT 토너먼트 + 주최자(ownerTU) + 참여자(TU) fixture. 결과 알림 수신자 해석(참여자 − 주최자)이 동작하도록 깐다.
    private fun createRootWithOwnerAndParticipant(
        ownerUserId: UUID,
        participantUserId: UUID,
    ): Long {
        val root = tournamentRepository.saveTournament(
            Tournament(ownerTournamentUserId = 0L, name = "t", inviteCode = "ROOT01", inviteExpiresAt = LocalDateTime.now().plusDays(1)),
        )
        val ownerTu = tournamentUserRepository.save(TournamentUser(root.getId(), ownerUserId))
        root.assignOwner(ownerTu.getId())
        tournamentRepository.saveTournament(root)
        tournamentUserRepository.save(TournamentUser(root.getId(), participantUserId))
        return root.getId()
    }
}
