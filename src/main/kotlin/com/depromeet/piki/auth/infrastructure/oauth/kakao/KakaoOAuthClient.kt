package com.depromeet.piki.auth.infrastructure.oauth.kakao

import com.depromeet.piki.auth.infrastructure.oauth.OAuthClient
import com.depromeet.piki.auth.infrastructure.oauth.OAuthParams
import com.depromeet.piki.auth.infrastructure.oauth.OAuthProvider
import com.depromeet.piki.auth.infrastructure.oauth.OAuthRestClient
import com.depromeet.piki.auth.infrastructure.oauth.OAuthUserInfo
import com.depromeet.piki.auth.infrastructure.oauth.kakao.dto.KakaoTokenResponse
import com.depromeet.piki.auth.infrastructure.oauth.kakao.dto.KakaoUserInfoResponse
import com.depromeet.piki.auth.infrastructure.oauth.logOAuthProviderError
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpResponse
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.body
import org.springframework.web.util.UriComponentsBuilder

class KakaoOAuthClient(
    private val kakaoProperties: KakaoProperties,
    observationRegistry: ObservationRegistry,
) : OAuthClient {
    override val provider = OAuthProvider.KAKAO

    private val log = LoggerFactory.getLogger(javaClass)

    private val authClient = OAuthRestClient.create(AUTH_BASE_URL, observationRegistry)
    private val apiClient = OAuthRestClient.create(API_BASE_URL, observationRegistry)

    override fun buildAuthUrl(state: String, redirectUri: String?): String =
        UriComponentsBuilder
            .fromUriString("$AUTH_BASE_URL/oauth/authorize")
            .queryParam(OAuthParams.CLIENT_ID, kakaoProperties.clientId)
            .queryParam(OAuthParams.REDIRECT_URI, redirectUri ?: kakaoProperties.redirectUri)
            .queryParam(OAuthParams.RESPONSE_TYPE, OAuthParams.RESPONSE_TYPE_CODE)
            .queryParam(OAuthParams.STATE, state)
            .build()
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
    // Kakao 의 access_token 은 v1/v2 모두 같은 종류라 추가 검증 로직 없이 그대로 재활용 가능하다.
    override fun fetchUserInfoByAccessToken(accessToken: String): OAuthUserInfo = fetchKakaoUserInfo(accessToken).toOAuthUserInfo()

    private fun fetchAccessToken(
        code: String,
        redirectUri: String,
    ): String {
        val params =
            LinkedMultiValueMap<String, String>().apply {
                add(OAuthParams.GRANT_TYPE, OAuthParams.GRANT_TYPE_AUTHORIZATION_CODE)
                add(OAuthParams.CLIENT_ID, kakaoProperties.clientId)
                add(OAuthParams.CLIENT_SECRET, kakaoProperties.clientSecret)
                add(OAuthParams.REDIRECT_URI, redirectUri)
                add(OAuthParams.CODE, code)
            }
        val response =
            authClient
                .post()
                .uri(TOKEN_PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(params)
                .retrieve()
                // token endpoint(error_code 문자열) 와 user API(정수 code) 는 바디 포맷이 달라
                // classifier 의 분리된 분류 경로로 넘긴다. 4xx/5xx 를 시맨틱(400/401/502)으로 재매핑.
                .onStatus({ it.isError }) { _, res ->
                    val body = res.readBodyAsString()
                    val exception = KakaoOAuthErrorClassifier.classifyTokenError(res.statusCode, body)
                    logOAuthProviderError(log, "Kakao", "token", res.statusCode.value(), body, exception.category)
                    throw exception
                }.body<KakaoTokenResponse>()
                ?: error("Kakao token response body is null")
        return response.accessToken
    }

    private fun fetchKakaoUserInfo(accessToken: String): KakaoUserInfoResponse =
        apiClient
            .get()
            .uri(USER_INFO_PATH)
            .header(HttpHeaders.AUTHORIZATION, "$BEARER_PREFIX$accessToken")
            .retrieve()
            .onStatus({ it.isError }) { _, res ->
                val body = res.readBodyAsString()
                val exception = KakaoOAuthErrorClassifier.classifyUserApiError(res.statusCode, body)
                logOAuthProviderError(log, "Kakao", "userApi", res.statusCode.value(), body, exception.category)
                throw exception
            }.body<KakaoUserInfoResponse>()
            ?: error("Kakao user info response body is null")

    // 에러 응답 바디를 문자열로 읽어 classifier 에 넘긴다. classifier 는 정수 code 만으로 분기하므로
    // 인코딩이 깨져도 분기 자체는 안전하다(미지 code → 502 fallback).
    private fun ClientHttpResponse.readBodyAsString(): String = body.readBytes().toString(Charsets.UTF_8)

    companion object {
        private const val AUTH_BASE_URL = "https://kauth.kakao.com"
        private const val API_BASE_URL = "https://kapi.kakao.com"
        private const val TOKEN_PATH = "/oauth/token"
        private const val USER_INFO_PATH = "/v2/user/me"
        private const val BEARER_PREFIX = "Bearer "
    }
}
