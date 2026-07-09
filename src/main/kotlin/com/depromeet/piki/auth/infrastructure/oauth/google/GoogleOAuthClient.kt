package com.depromeet.piki.auth.infrastructure.oauth.google

import com.depromeet.piki.auth.infrastructure.oauth.OAuthClient
import com.depromeet.piki.auth.infrastructure.oauth.OAuthParams
import com.depromeet.piki.auth.infrastructure.oauth.OAuthProvider
import com.depromeet.piki.auth.infrastructure.oauth.OAuthRestClient
import com.depromeet.piki.auth.infrastructure.oauth.OAuthUserInfo
import com.depromeet.piki.auth.infrastructure.oauth.google.dto.GoogleTokenResponse
import com.depromeet.piki.auth.infrastructure.oauth.google.dto.GoogleUserInfoResponse
import com.depromeet.piki.auth.infrastructure.oauth.logOAuthProviderError
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpResponse
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.StreamUtils
import org.springframework.web.client.body
import org.springframework.web.util.UriComponentsBuilder
import java.nio.charset.StandardCharsets

class GoogleOAuthClient(
    private val googleProperties: GoogleProperties,
) : OAuthClient {
    override val provider = OAuthProvider.GOOGLE

    private val log = LoggerFactory.getLogger(javaClass)

    private val tokenClient = OAuthRestClient.create(TOKEN_BASE_URL)
    private val userInfoClient = OAuthRestClient.create(USER_INFO_BASE_URL)

    override fun buildAuthUrl(state: String, redirectUri: String?): String =
        UriComponentsBuilder
            .fromUriString(AUTH_URL)
            .queryParam(OAuthParams.CLIENT_ID, googleProperties.clientId)
            .queryParam(OAuthParams.REDIRECT_URI, redirectUri ?: googleProperties.redirectUri)
            .queryParam(OAuthParams.RESPONSE_TYPE, OAuthParams.RESPONSE_TYPE_CODE)
            .queryParam(OAuthParams.SCOPE, "email profile")
            .queryParam(OAuthParams.STATE, state)
            .build()
            .encode()
            .toUriString()

    // v1 — 백엔드가 code → access_token 교환 후 v2 메서드를 재사용해 user_info 조회.
    override fun fetchUserInfoByCode(
        code: String,
        redirectUri: String,
    ): OAuthUserInfo {
        val accessToken = fetchAccessToken(code, redirectUri)
        return fetchUserInfoByAccessToken(accessToken)
    }

    // v2 — 클라이언트 SDK 가 받은 access_token 으로 user_info 만 조회.
    // Google SDK 는 id_token (JWT) 도 같이 반환하는데, 그 흐름은 JWKS public key 검증 로직이
    // 추가로 필요해 epic #122 (https://github.com/TeamPiKi/core/issues/122) 의
    // Task 6 에서 별도 메서드로 구현한다. 본 PR 은 access_token 흐름까지만.
    override fun fetchUserInfoByAccessToken(accessToken: String): OAuthUserInfo = fetchGoogleUserInfo(accessToken).toOAuthUserInfo()

    private fun fetchAccessToken(
        code: String,
        redirectUri: String,
    ): String {
        val params =
            LinkedMultiValueMap<String, String>().apply {
                add(OAuthParams.GRANT_TYPE, OAuthParams.GRANT_TYPE_AUTHORIZATION_CODE)
                add(OAuthParams.CLIENT_ID, googleProperties.clientId)
                add(OAuthParams.CLIENT_SECRET, googleProperties.clientSecret)
                add(OAuthParams.REDIRECT_URI, redirectUri)
                add(OAuthParams.CODE, code)
            }
        val response =
            tokenClient
                .post()
                .uri(TOKEN_PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(params)
                .retrieve()
                // 4xx/5xx 응답은 바디를 classifier 로 분류해 401/400/500/502 OAuthException 으로 throw.
                .onStatus({ it.isError }) { _, res -> throwClassified(GoogleOAuthEndpoint.TOKEN, res) }
                .body<GoogleTokenResponse>()
                ?: error("Google token response body is null")
        return response.accessToken
    }

    private fun fetchGoogleUserInfo(accessToken: String): GoogleUserInfoResponse =
        userInfoClient
            .get()
            .uri(USER_INFO_PATH)
            .header(HttpHeaders.AUTHORIZATION, "$BEARER_PREFIX$accessToken")
            .retrieve()
            // access_token 무효/만료(HTTP 401)는 invalidProviderToken(401), 그 외는 502 fallback.
            .onStatus({ it.isError }) { _, res -> throwClassified(GoogleOAuthEndpoint.USER_INFO, res) }
            .body<GoogleUserInfoResponse>()
            ?: error("Google user info response body is null")

    // 에러 응답 바디·status 를 순수 함수 classifier 로 분류해 throw. 분류 결과는 OAuthException 이라
    // runProvider 의 catch (e: OAuthException) { throw e } 가 그대로 통과시켜 정확한 status 가 내려간다.
    private fun throwClassified(
        endpoint: GoogleOAuthEndpoint,
        response: ClientHttpResponse,
    ): Nothing {
        val statusCode = response.statusCode.value()
        val body = StreamUtils.copyToString(response.body, StandardCharsets.UTF_8)
        val exception = GoogleOAuthErrorClassifier.classify(endpoint, statusCode, body)
        logOAuthProviderError(log, "Google", endpoint.name, statusCode, body, exception.category)
        throw exception
    }

    companion object {
        private const val TOKEN_BASE_URL = "https://oauth2.googleapis.com"
        private const val USER_INFO_BASE_URL = "https://www.googleapis.com"
        private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_PATH = "/token"
        private const val USER_INFO_PATH = "/oauth2/v2/userinfo"
        private const val BEARER_PREFIX = "Bearer "
    }
}
