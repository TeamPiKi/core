package com.depromeet.piki.auth.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.notification.fcm.controller.dto.FcmTokenRegisterRequest
import com.depromeet.piki.notification.fcm.repository.UserDeviceRepository
import com.depromeet.piki.notification.fcm.web.DeviceCookie
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.user.domain.IdentityType
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// 로그아웃이 세션뿐 아니라 그 기기의 푸시 수신까지 끊는지 검증한다(#922).
// 안 끊기면 로그아웃한 기기에 그 계정의 알림이 계속 뜨고, 발송 payload 가 표시 블록이라 앱이 거를 수도 없다.
//
// 기기는 device_id 쿠키로 가른다 — 클라가 FCM 등록 시점에 심어 둔 값이라 별도 요청 필드가 없다.
// /api/v1/auth/logout 과 /api/v1/fcm/** 는 users row 없이 JWT 만으로 호출된다(권한 검사 없음).
@Transactional
class LogoutDeviceCleanupIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Autowired private lateinit var userDeviceRepository: UserDeviceRepository

    private fun buildMockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun accessToken(userId: UUID): String = jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)

    // 실제 등록 경로를 그대로 탄다 — 리포지토리에 직접 심으면 등록/해제 키 정규화가 어긋나도 통과해버린다.
    private fun MockMvc.registerDevice(
        userId: UUID,
        deviceId: String,
        token: String,
    ) {
        perform(
            post("/api/v1/fcm/tokens")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${accessToken(userId)}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(FcmTokenRegisterRequest(token = token, deviceId = deviceId))),
        ).andExpect(status().isOk)
    }

    @Test
    fun `로그아웃하면 device_id 쿠키가 가리키는 기기의 FCM 토큰이 해제된다`() {
        val userId = UUID.randomUUID()
        val mvc = buildMockMvc()
        mvc.registerDevice(userId, deviceId = "device-1", token = "token-1")

        mvc
            .perform(
                post("/api/v1/auth/logout")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${accessToken(userId)}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .cookie(Cookie(DeviceCookie.DEVICE_ID, "device-1")),
            ).andExpect(status().isOk)

        assertTrue(userDeviceRepository.findAllByUserId(userId).isEmpty(), "로그아웃한 기기의 FCM 토큰이 남아 있다")
    }

    @Test
    fun `한 기기에서 로그아웃해도 다른 기기의 푸시 등록은 유지된다`() {
        val userId = UUID.randomUUID()
        val mvc = buildMockMvc()
        mvc.registerDevice(userId, deviceId = "device-1", token = "token-1")
        mvc.registerDevice(userId, deviceId = "device-2", token = "token-2")

        mvc
            .perform(
                post("/api/v1/auth/logout")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${accessToken(userId)}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .cookie(Cookie(DeviceCookie.DEVICE_ID, "device-1")),
            ).andExpect(status().isOk)

        val remaining = userDeviceRepository.findAllByUserId(userId)
        assertEquals(1, remaining.size, "다른 기기의 푸시 등록까지 지워졌다")
        assertEquals("device-2", remaining.first().deviceId)
    }

    // device_id 쿠키를 안 보내는 클라이언트(푸시 미등록 기기 포함)도 로그아웃은 그대로 성공해야 한다.
    // 기기를 특정할 수 없으니 해제 대상도 없다 — #922 이전과 같은 동작이라 배포 순서 의존이 없다.
    @Test
    fun `device_id 쿠키가 없으면 기기 해제 없이 로그아웃만 성공한다`() {
        val userId = UUID.randomUUID()
        val mvc = buildMockMvc()
        mvc.registerDevice(userId, deviceId = "device-1", token = "token-1")

        mvc
            .perform(
                post("/api/v1/auth/logout")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${accessToken(userId)}")
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isOk)

        val saved = userDeviceRepository.findByUserIdAndDeviceId(userId, "device-1")
        assertNotNull(saved, "쿠키가 없는데 기기가 해제됐다")
        assertEquals("token-1", saved.fcmToken)
    }
}
