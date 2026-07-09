package com.depromeet.piki.auth.infrastructure.oauth.kakao.dto

import com.depromeet.piki.auth.infrastructure.oauth.OAuthProvider
import com.depromeet.piki.auth.infrastructure.oauth.OAuthUserInfo
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

// 카카오 /v2/user/me 응답 wire 모델. 우리가 쓰는 필드(profile_image_url·email·email 상태)만 모델링하고,
// 나머지(has_email·email_needs_agreement·connected_at 등 다수)는 ignoreUnknown 으로 무시한다 —
// 운영 RestClient 의 ObjectMapper 설정에 의존하지 않고 DTO 자체가 부분 매핑을 명시한다(GoogleUserInfoResponse 와 동일).
@JsonIgnoreProperties(ignoreUnknown = true)
data class KakaoUserInfoResponse(
    val id: Long,
    @JsonProperty("kakao_account") val kakaoAccount: KakaoAccount,
) {
    // 외부 응답 → 도메인 매핑 (CLAUDE.md: 외부 결과 객체의 toXxx()).
    // profileImage 는 빈 문자열이면 null. email 은 비즈 앱 검수 통과 후 수집(#451) — 유효·인증된 값만 채운다.
    fun toOAuthUserInfo(): OAuthUserInfo =
        OAuthUserInfo(
            provider = OAuthProvider.KAKAO,
            socialId = id.toString(),
            profileImage = kakaoAccount.profile.profileImageUrl.ifBlank { null },
            email = kakaoAccount.verifiedEmailOrNull(),
        )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class KakaoAccount(
        val profile: Profile = Profile(),
        // 카카오계정 email 과 상태 플래그. 미동의면 email 자체가 응답에서 빠질 수 있어 nullable·기본 false.
        @JsonProperty("email") val email: String? = null,
        @JsonProperty("is_email_valid") val isEmailValid: Boolean = false,
        @JsonProperty("is_email_verified") val isEmailVerified: Boolean = false,
    ) {
        // 유효(미휴면)하고 인증된 email 만 신뢰한다 — 마케팅·알림·복구용이라 미인증·휴면 주소는 수집하지 않는다.
        // 미동의(email null) · 미인증/휴면(플래그 false) · 빈값은 모두 null.
        fun verifiedEmailOrNull(): String? = email?.takeIf { isEmailValid && isEmailVerified }?.ifBlank { null }

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Profile(
            @JsonProperty("profile_image_url") val profileImageUrl: String = "",
        )
    }
}
