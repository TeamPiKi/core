package com.depromeet.piki.notification.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.notification.controller.dto.NotificationReadRequest
import com.depromeet.piki.notification.domain.Notification
import com.depromeet.piki.notification.domain.NotificationRouting
import com.depromeet.piki.notification.domain.NotificationType
import com.depromeet.piki.notification.repository.NotificationJpaRepository
import com.depromeet.piki.notification.repository.NotificationRepository
import com.depromeet.piki.notification.service.DefaultPushImage
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.user.domain.IdentityType
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Transactional
class NotificationHistoryControllerIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Autowired private lateinit var notificationRepository: NotificationRepository

    @Autowired private lateinit var notificationJpaRepository: NotificationJpaRepository

    @Autowired private lateinit var defaultPushImage: DefaultPushImage

    private fun authHeader(userId: UUID): String = "Bearer ${jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)}"

    private fun buildMockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun seed(
        userId: UUID,
        isRead: Boolean = false,
        routing: NotificationRouting? = null,
    ): Long {
        val saved =
            notificationRepository.save(
                Notification(userId, NotificationType.ITEM_PARSING_COMPLETED, "제목", "본문", 11L, routing),
            )
        if (isRead) {
            saved.markRead()
            notificationRepository.save(saved)
        }
        return saved.getId()
    }

    // 타입·actor 프사 snapshot 을 지정해 저장 — 카테고리 필터·imageUrl 검증용.
    private fun seedTyped(
        userId: UUID,
        type: NotificationType,
        actorImageUrl: String? = null,
    ): Long =
        notificationRepository
            .save(Notification(userId, type, "제목", "본문", 11L, routing = null, actorImageUrl = actorImageUrl))
            .getId()

    @Test
    fun `본인 알림만 최신순으로 unreadCount 와 함께 조회된다`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val oldest = seed(userId, isRead = true)
        val middle = seed(userId, isRead = false, routing = NotificationRouting.Tournament(99L, 555L))
        val newest = seed(userId, isRead = false, routing = NotificationRouting.Wish)
        seed(otherUserId, isRead = false) // 타인 알림 — 결과에 섞이면 안 됨

        buildMockMvc()
            .perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, authHeader(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(3))
            // 최신순(id desc): newest → middle → oldest
            .andExpect(jsonPath("$.data.items[0].id").value(newest))
            .andExpect(jsonPath("$.data.items[0].kind").value("WISH"))
            .andExpect(jsonPath("$.data.items[1].id").value(middle))
            .andExpect(jsonPath("$.data.items[1].kind").value("TOURNAMENT"))
            .andExpect(jsonPath("$.data.items[1].tournamentId").value(99))
            .andExpect(jsonPath("$.data.items[1].tournamentItemId").value(555))
            .andExpect(jsonPath("$.data.items[2].id").value(oldest))
            .andExpect(jsonPath("$.data.items[2].isRead").value(true))
            .andExpect(jsonPath("$.data.unreadCount").value(2))
            .andExpect(jsonPath("$.pageResponse.hasNext").value(false))
            .andExpect(jsonPath("$.pageResponse.nextCursor").value(nullValue()))
    }

    @Test
    fun `category=ACTIVITY 면 토너먼트 알림만, category=SYSTEM 이면 파싱 알림만 조회된다`() {
        val userId = UUID.randomUUID()
        val activity = seedTyped(userId, NotificationType.TOURNAMENT_STARTED)
        val system = seedTyped(userId, NotificationType.ITEM_PARSING_COMPLETED)
        val mockMvc = buildMockMvc()

        mockMvc
            .perform(get("/api/v1/notifications").param("category", "ACTIVITY").header(HttpHeaders.AUTHORIZATION, authHeader(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(activity))
            .andExpect(jsonPath("$.data.items[0].category").value("ACTIVITY"))

        mockMvc
            .perform(get("/api/v1/notifications").param("category", "SYSTEM").header(HttpHeaders.AUTHORIZATION, authHeader(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(system))
            .andExpect(jsonPath("$.data.items[0].category").value("SYSTEM"))
    }

    @Test
    fun `category 필터도 커서로 페이지를 나누고 다음 페이지를 잇는다`() {
        val userId = UUID.randomUUID()
        val a1 = seedTyped(userId, NotificationType.TOURNAMENT_JOINED)
        val a2 = seedTyped(userId, NotificationType.TOURNAMENT_ITEM_ADDED)
        val a3 = seedTyped(userId, NotificationType.TOURNAMENT_STARTED)
        seedTyped(userId, NotificationType.ITEM_PARSING_COMPLETED) // 시스템 — ACTIVITY 페이징에 섞이면 안 됨
        val mockMvc = buildMockMvc()

        // 1페이지: ACTIVITY size=2 → 최신 2건(a3, a2), 다음 페이지 있음, 커서=a2 (type-in 커서 변형 검증)
        mockMvc
            .perform(
                get("/api/v1/notifications")
                    .param("category", "ACTIVITY")
                    .param("size", "2")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].id").value(a3))
            .andExpect(jsonPath("$.data.items[1].id").value(a2))
            .andExpect(jsonPath("$.pageResponse.hasNext").value(true))
            .andExpect(jsonPath("$.pageResponse.nextCursor").value(a2.toString()))

        // 2페이지: cursor=a2 + ACTIVITY → 남은 활동 1건(a1)만, 시스템 알림은 안 섞임
        mockMvc
            .perform(
                get("/api/v1/notifications")
                    .param("category", "ACTIVITY")
                    .param("size", "2")
                    .param("cursor", a2.toString())
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(a1))
            .andExpect(jsonPath("$.pageResponse.hasNext").value(false))
            .andExpect(jsonPath("$.pageResponse.nextCursor").value(nullValue()))
    }

    @Test
    fun `category 로 걸러도 unreadCount 는 필터와 무관한 전체다`() {
        val userId = UUID.randomUUID()
        seedTyped(userId, NotificationType.TOURNAMENT_STARTED) // 활동 1
        seedTyped(userId, NotificationType.ITEM_PARSING_COMPLETED) // 시스템 1
        seedTyped(userId, NotificationType.ITEM_PARSING_FAILED) // 시스템 1

        // category=ACTIVITY 로 목록은 1건만 걸러지지만, unreadCount(앱 badge)는 전체 3.
        buildMockMvc()
            .perform(get("/api/v1/notifications").param("category", "ACTIVITY").header(HttpHeaders.AUTHORIZATION, authHeader(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.unreadCount").value(3))
    }

    @Test
    fun `유효하지 않은 category 는 400 으로 거른다`() {
        buildMockMvc()
            .perform(
                get("/api/v1/notifications")
                    .param("category", "NOPE")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(UUID.randomUUID())),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail", notNullValue()))
    }

    @Test
    fun `actor 알림은 imageUrl 이 프사 snapshot, 시스템 알림은 defaultPushImg 로 채워진다`() {
        val userId = UUID.randomUUID()
        val actorImage = "https://img.test/profiles/actor.png"
        val activity = seedTyped(userId, NotificationType.TOURNAMENT_JOINED, actorImageUrl = actorImage)
        val system = seedTyped(userId, NotificationType.ITEM_PARSING_COMPLETED, actorImageUrl = null)

        buildMockMvc()
            .perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, authHeader(userId)))
            .andExpect(status().isOk)
            // 최신순(id desc): system → activity
            .andExpect(jsonPath("$.data.items[0].id").value(system))
            .andExpect(jsonPath("$.data.items[0].imageUrl").value(defaultPushImage.url)) // actor 없음 → 서버가 채운 기본 아바타
            .andExpect(jsonPath("$.data.items[0].category").value("SYSTEM"))
            .andExpect(jsonPath("$.data.items[1].id").value(activity))
            .andExpect(jsonPath("$.data.items[1].imageUrl").value(actorImage)) // 발송 시점 프사 snapshot 그대로
            .andExpect(jsonPath("$.data.items[1].category").value("ACTIVITY"))
    }

    @Test
    fun `size 로 페이지를 나누고 nextCursor 로 다음 페이지를 잇는다`() {
        val userId = UUID.randomUUID()
        val first = seed(userId)
        val second = seed(userId)
        val third = seed(userId)
        val mockMvc = buildMockMvc()

        // 1페이지: size=2 → 최신 2건(third, second), 다음 페이지 있음, 커서=second
        mockMvc
            .perform(
                get("/api/v1/notifications")
                    .param("size", "2")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].id").value(third))
            .andExpect(jsonPath("$.data.items[1].id").value(second))
            .andExpect(jsonPath("$.pageResponse.hasNext").value(true))
            .andExpect(jsonPath("$.pageResponse.nextCursor").value(second.toString()))

        // 2페이지: cursor=second → 남은 1건(first), 다음 페이지 없음
        mockMvc
            .perform(
                get("/api/v1/notifications")
                    .param("size", "2")
                    .param("cursor", second.toString())
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(first))
            .andExpect(jsonPath("$.pageResponse.hasNext").value(false))
            .andExpect(jsonPath("$.pageResponse.nextCursor").value(nullValue()))
    }

    @Test
    fun `유효하지 않은 cursor 는 400 NOTIFICATION-001 로 거른다`() {
        val userId = UUID.randomUUID()
        buildMockMvc()
            .perform(
                get("/api/v1/notifications")
                    .param("cursor", "abc")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("NOTIFICATION-001"))
    }

    @Test
    fun `유효하지 않은 category 값은 400 COMMON-INVALID-INPUT 로 거른다`() {
        // category 는 NotificationCategory enum 바인딩이라 미지 값은 MethodArgumentTypeMismatchException →
        // RESEH → 400. 도메인 예외가 아니므로 공통 폴백 code(COMMON-INVALID-INPUT)를 받는다.
        val userId = UUID.randomUUID()
        buildMockMvc()
            .perform(
                get("/api/v1/notifications")
                    .param("category", "UNKNOWN")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("COMMON-INVALID-INPUT"))
    }

    @Test
    fun `토큰 없이 조회하면 401 이 ApiResponseBody contract 로 내려간다`() {
        buildMockMvc()
            .perform(get("/api/v1/notifications"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.detail", notNullValue()))
    }

    @Test
    fun `read all 은 본인 안읽음만 전부 읽음 처리하고 타인 알림은 무영향이다`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        seed(userId, isRead = false)
        seed(userId, isRead = false)
        val others = seed(otherUserId, isRead = false) // all=true 가 user_id 범위를 안 넘는지 검증 (WHERE user_id=? 회귀 가드)
        val mockMvc = buildMockMvc()

        mockMvc
            .perform(
                post("/api/v1/notifications/read")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"all":true}""")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            // 처리 후 안읽음 수를 응답으로 바로 내려준다(badge 미러링용) — 별도 GET 불필요.
            .andExpect(jsonPath("$.data.unreadCount").value(0))

        mockMvc
            .perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, authHeader(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.unreadCount").value(0))
            .andExpect(jsonPath("$.data.items[0].isRead").value(true))
            .andExpect(jsonPath("$.data.items[1].isRead").value(true))

        // 타인(otherUserId) 알림은 all=true 에 안 휩쓸려 그대로 안읽음 (markAllRead 의 user_id 한정 검증).
        assertFalse(notificationJpaRepository.findById(others).get().isRead)
    }

    @Test
    fun `read ids 는 지정한 본인 알림만 읽음 처리하고 타인 알림은 무영향이다`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val target = seed(userId, isRead = false)
        val untouched = seed(userId, isRead = false)
        val others = seed(otherUserId, isRead = false)

        buildMockMvc()
            .perform(
                post("/api/v1/notifications/read")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"ids":[$target,$others]}""")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            // target 만 본인 소유라 읽힘 → 본인 안읽음은 untouched 1건 남는다(others 는 타인이라 무영향).
            .andExpect(jsonPath("$.data.unreadCount").value(1))

        // 지정 + 본인 소유만 read. 미지정 본인 것·타인 것은 그대로(소유 검증은 쿼리 user_id 가 겸한다).
        assertTrue(notificationJpaRepository.findById(target).get().isRead)
        assertFalse(notificationJpaRepository.findById(untouched).get().isRead)
        assertFalse(notificationJpaRepository.findById(others).get().isRead)
    }

    // read 후 silent badge 동기화 push 는 @Async(notificationExecutor)라 요청 스레드 밖·새 트랜잭션에서 돈다.
    // @Transactional 자동 롤백 컨텍스트에선 워커가 미커밋 데이터를 못 보므로, 그 검증은
    // NotificationBadgeSyncAsyncIntegrationTest(실제 커밋 + Awaitility)가 맡는다.

    @Test
    fun `all 과 ids 를 함께 보내면 400 이고 detail 에 위반 메시지가 실린다`() {
        val userId = UUID.randomUUID()
        buildMockMvc()
            .perform(
                post("/api/v1/notifications/read")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"all":true,"ids":[1]}""")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(NotificationReadRequest.VALID_SELECTION_MESSAGE))
    }

    @Test
    fun `빈 ids 만 보내면 400 이고 detail 에 위반 메시지가 실린다`() {
        val userId = UUID.randomUUID()
        buildMockMvc()
            .perform(
                post("/api/v1/notifications/read")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"ids":[]}""")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(NotificationReadRequest.VALID_SELECTION_MESSAGE))
    }

    @Test
    fun `read 는 멱등이라 이미 읽은 알림에 다시 보내도 200 이다`() {
        val userId = UUID.randomUUID()
        seed(userId, isRead = false)
        val mockMvc = buildMockMvc()

        repeat(2) {
            mockMvc
                .perform(
                    post("/api/v1/notifications/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"all":true}""")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
                ).andExpect(status().isOk)
                // 멱등 — 이미 읽은 뒤 재요청해도 응답 unreadCount 는 0 으로 흔들리지 않는다.
                .andExpect(jsonPath("$.data.unreadCount").value(0))
        }

        mockMvc
            .perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, authHeader(userId)))
            .andExpect(jsonPath("$.data.unreadCount").value(0))
    }
}
