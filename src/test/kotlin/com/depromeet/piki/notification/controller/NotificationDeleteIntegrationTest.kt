package com.depromeet.piki.notification.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.notification.controller.dto.NotificationDeleteRequest
import com.depromeet.piki.notification.domain.Notification
import com.depromeet.piki.notification.domain.NotificationType
import com.depromeet.piki.notification.repository.NotificationJpaRepository
import com.depromeet.piki.notification.repository.NotificationRepository
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.user.domain.IdentityType
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Transactional
class NotificationDeleteIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Autowired private lateinit var notificationRepository: NotificationRepository

    @Autowired private lateinit var notificationJpaRepository: NotificationJpaRepository

    private fun authHeader(userId: UUID): String = "Bearer ${jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)}"

    private fun buildMockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun seed(
        userId: UUID,
        type: NotificationType = NotificationType.ITEM_PARSING_COMPLETED,
    ): Long =
        notificationRepository
            .save(Notification(userId, type, "제목", "본문", 11L))
            .getId()

    // 삭제 벌크 쿼리(hardDeleteAllByUserId·deleteByUserIdAndIds)가 clearAutomatically 로 컨텍스트를 비워
    // findById(PK)도 벌크 삭제 후 DB 를 다시 쳐 삭제 결과를 정확히 반영한다.
    private fun exists(id: Long): Boolean = notificationJpaRepository.findById(id).isPresent

    @Test
    fun `단건 삭제 - ids 로 지정한 본인 알림만 삭제되고 badge 가 재계산된다`() {
        val userId = UUID.randomUUID()
        val activity = seed(userId, NotificationType.TOURNAMENT_STARTED) // 안읽음 — 남아야 함
        val system = seed(userId, NotificationType.ITEM_PARSING_COMPLETED) // 안읽음 — 삭제 대상

        buildMockMvc()
            .perform(
                delete("/api/v1/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"ids":[$system]}""")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            // system 삭제 후 남은 안읽음은 1건 — 앱 badge 1.
            .andExpect(jsonPath("$.data.unreadCount").value(1))
            // 삭제 응답은 히스토리와 별개 DTO 라 카테고리 맵 부재를 여기서 따로 잠근다.
            .andExpect(jsonPath("$.data.unreadCountByCategory").doesNotExist())

        assertFalse(exists(system))
        assertTrue(exists(activity))
    }

    @Test
    fun `다건 삭제 - 여러 id 를 한 번에 삭제하고 지정 안 한 것은 남긴다`() {
        val userId = UUID.randomUUID()
        val a = seed(userId)
        val b = seed(userId)
        val c = seed(userId)

        buildMockMvc()
            .perform(
                delete("/api/v1/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"ids":[$a,$b]}""")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.unreadCount").value(1))

        assertFalse(exists(a))
        assertFalse(exists(b))
        assertTrue(exists(c))
    }

    @Test
    fun `모두 삭제 - all=true 는 본인 알림 전부 삭제하고 타인 알림은 무영향이다`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val mine1 = seed(userId)
        val mine2 = seed(userId)
        val others = seed(otherUserId) // all=true 가 user_id 범위를 안 넘는지 검증 (WHERE user_id=? 회귀 가드)

        buildMockMvc()
            .perform(
                delete("/api/v1/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"all":true}""")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.unreadCount").value(0))
            .andExpect(jsonPath("$.data.unreadCountByCategory").doesNotExist())

        assertFalse(exists(mine1))
        assertFalse(exists(mine2))
        assertTrue(exists(others))
    }

    @Test
    fun `ids 에 타인 id 가 섞여도 본인 것만 삭제된다 (소유 검증·멱등)`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val target = seed(userId)
        val untouched = seed(userId)
        val others = seed(otherUserId)

        buildMockMvc()
            .perform(
                delete("/api/v1/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"ids":[$target,$others]}""")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            // target 만 본인 소유라 삭제 → 본인 안읽음은 untouched 1건 남는다(others 는 타인이라 무영향).
            .andExpect(jsonPath("$.data.unreadCount").value(1))

        assertFalse(exists(target))
        assertTrue(exists(untouched))
        assertTrue(exists(others))
    }

    @Test
    fun `없는 id 를 삭제해도 멱등이라 200 이다`() {
        val userId = UUID.randomUUID()

        buildMockMvc()
            .perform(
                delete("/api/v1/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"ids":[999999]}""")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.unreadCount").value(0))
    }

    @Test
    fun `all 과 ids 를 함께 보내면 400 이고 detail 에 위반 메시지가 실린다`() {
        val userId = UUID.randomUUID()
        buildMockMvc()
            .perform(
                delete("/api/v1/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"all":true,"ids":[1]}""")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(NotificationDeleteRequest.VALID_SELECTION_MESSAGE))
    }

    @Test
    fun `빈 ids 만 보내면 400 이고 detail 에 위반 메시지가 실린다`() {
        val userId = UUID.randomUUID()
        buildMockMvc()
            .perform(
                delete("/api/v1/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"ids":[]}""")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(NotificationDeleteRequest.VALID_SELECTION_MESSAGE))
    }

    @Test
    fun `토큰 없이 삭제하면 401 이 ApiResponseBody contract 로 내려간다`() {
        buildMockMvc()
            .perform(
                delete("/api/v1/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"all":true}"""),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.detail", notNullValue()))
    }
}
