package com.depromeet.piki.auth.controller

import com.depromeet.piki.auth.controller.dto.GuestCreateResponse
import com.depromeet.piki.auth.controller.dto.LogoutResponse
import com.depromeet.piki.auth.controller.dto.TokenRefreshRequest
import com.depromeet.piki.auth.controller.dto.TokenRefreshResponse
import com.depromeet.piki.auth.exception.AuthException
import com.depromeet.piki.auth.service.AuthService
import com.depromeet.piki.auth.web.TokenCookieWriter
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.notification.fcm.web.DeviceCookie
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
) : AuthApi {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/guest")
    @ResponseStatus(HttpStatus.CREATED)
    override fun createGuest(): ApiResponseBody<GuestCreateResponse> {
        val result = authService.createGuest()
        return ApiResponseBody.created(GuestCreateResponse.from(result.tokenPair, result.user))
    }

    // refresh 토큰을 쿠키(WEB) 또는 body(APP) 어느 쪽에서든 받는다. 둘 다 없으면 400.
    // 쿠키 정책(HttpOnly·SameSite 등)은 TokenCookieWriter/advice 가 소유하고, 여기선 입력만 읽는다.
    @PostMapping("/token/refresh")
    override fun refresh(
        @Valid @RequestBody(required = false) request: TokenRefreshRequest?,
        @CookieValue(name = TokenCookieWriter.REFRESH_COOKIE, required = false) cookieRefreshToken: String?,
    ): ApiResponseBody<TokenRefreshResponse> {
        // 빈 쿠키 값은 없는 것으로 본다 — 빈 쿠키가 body 입력을 가리지 않도록.
        val cookieToken = cookieRefreshToken?.ifBlank { null }
        val refreshToken = cookieToken ?: request?.refreshToken ?: throw AuthException.refreshTokenRequired()
        log.info("토큰 갱신 요청: refreshToken 출처={}", cookieToken?.let { "cookie" } ?: "body")
        val tokenPair = authService.refresh(refreshToken)
        return ApiResponseBody.ok(TokenRefreshResponse.from(tokenPair))
    }

    // 로그아웃은 이 기기의 세션만 끊는다(#893). 어느 세션인지는 refresh 토큰의 sid 로 가르므로
    // 갱신과 같은 자리(쿠키 또는 body)에서 토큰을 읽는다. 토큰이 없으면 세션 특정이 불가해
    // 서비스가 전 세션 정리로 떨어진다(#893 이전 동작과 동일).
    //
    // 기기 식별자도 쿠키에서 함께 읽어 그 기기의 푸시 수신까지 끊는다(#922). 클라가 FCM 등록 시 심어 둔 쿠키라
    // 별도 요청 필드가 필요 없다. 없어도 로그아웃은 그대로 성공한다(FCM 미등록 기기).
    @PostMapping("/logout")
    override fun logout(
        @AuthenticationPrincipal userId: UUID,
        @RequestBody(required = false) request: TokenRefreshRequest?,
        @CookieValue(name = TokenCookieWriter.REFRESH_COOKIE, required = false) cookieRefreshToken: String?,
        @CookieValue(name = DeviceCookie.DEVICE_ID, required = false) cookieDeviceId: String?,
    ): ApiResponseBody<LogoutResponse> {
        val refreshToken = cookieRefreshToken?.ifBlank { null } ?: request?.refreshToken
        authService.logout(userId, refreshToken, cookieDeviceId)
        return ApiResponseBody.ok(LogoutResponse())
    }
}
