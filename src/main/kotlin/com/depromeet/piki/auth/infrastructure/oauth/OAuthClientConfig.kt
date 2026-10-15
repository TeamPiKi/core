package com.depromeet.piki.auth.infrastructure.oauth

import com.depromeet.piki.auth.infrastructure.oauth.apple.AppleOAuthClient
import com.depromeet.piki.auth.infrastructure.oauth.apple.AppleProperties
import com.depromeet.piki.auth.infrastructure.oauth.google.GoogleOAuthClient
import com.depromeet.piki.auth.infrastructure.oauth.google.GoogleProperties
import com.depromeet.piki.auth.infrastructure.oauth.kakao.KakaoOAuthClient
import com.depromeet.piki.auth.infrastructure.oauth.kakao.KakaoProperties
import io.micrometer.observation.ObservationRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// 운영 OAuthClient 빈 배선. 클라이언트는 Properties(env)만 의존하는 단순 객체라 @Component 대신 명시 @Bean.
// 통합 테스트는 oauth.client.enabled=false 로 이 설정을 꺼서, IntegrationStubs 의 stub 빈만
// 컨텍스트에 남게 한다 (외부 호출 격리, 빈 충돌 없음).
@Configuration
@ConditionalOnProperty(name = ["oauth.client.enabled"], havingValue = "true", matchIfMissing = true)
class OAuthClientConfig {
    @Bean
    fun googleOAuthClient(
        googleProperties: GoogleProperties,
        observationRegistry: ObservationRegistry,
    ): OAuthClient = GoogleOAuthClient(googleProperties, observationRegistry)

    @Bean
    fun kakaoOAuthClient(
        kakaoProperties: KakaoProperties,
        observationRegistry: ObservationRegistry,
    ): OAuthClient = KakaoOAuthClient(kakaoProperties, observationRegistry)

    // 반환 타입을 AppleOAuthClient 로 노출한다 — OAuthClient(소셜 로그인) 와 AppleNotificationVerifier
    // (서버-서버 알림 검증)를 한 인스턴스가 모두 구현하므로, 두 주입 지점에 같은 빈이 쓰여 JWKS 캐시를 공유한다.
    // 여전히 OAuthClient 구현이라 List<OAuthClient> 주입(소셜 로그인 registry)과도 호환된다.
    @Bean
    fun appleOAuthClient(
        appleProperties: AppleProperties,
        observationRegistry: ObservationRegistry,
    ): AppleOAuthClient = AppleOAuthClient(appleProperties, observationRegistry)
}
