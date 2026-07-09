package com.depromeet.piki.auth.infrastructure.oauth.google.dto

import com.depromeet.piki.auth.infrastructure.oauth.OAuthProvider
import com.depromeet.piki.auth.infrastructure.oauth.OAuthUserInfo
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

// Google userinfo 응답 wire 모델. 우리가 쓰는 필드(id·picture·email·verified_email)만 모델링하고,
// 나머지(name·locale 등)는 ignoreUnknown 으로 무시한다 — 운영 ObjectMapper 설정에
// 의존하지 않고 DTO 자체가 부분 매핑을 명시한다(KakaoUserInfoResponse 와 동일).
@JsonIgnoreProperties(ignoreUnknown = true)
data class GoogleUserInfoResponse(
    val id: String,
    val picture: String = "",
    // scope 에 email 이 있으나 사용자가 미동의하면 응답에 빠질 수 있어 nullable.
    val email: String? = null,
    // Google 은 미인증 email 도 응답에 포함할 수 있어 인증 여부를 verified_email 로 구분한다. 미동의면 빠질 수 있어 기본 false.
    @JsonProperty("verified_email") val verifiedEmail: Boolean = false,
) {
    // 외부 응답 → 도메인 매핑 (CLAUDE.md: 외부 결과 객체의 toXxx()). picture 는 빈 문자열이면 null 로 정규화.
    // email 은 인증된 값만 신뢰한다(Kakao 와 동일) — 미동의(null)·미인증(verified_email=false)·빈값은 모두 null.
    fun toOAuthUserInfo(): OAuthUserInfo =
        OAuthUserInfo(
            provider = OAuthProvider.GOOGLE,
            socialId = id,
            profileImage = picture.ifBlank { null },
            email = email?.takeIf { verifiedEmail }?.ifBlank { null },
        )
}
