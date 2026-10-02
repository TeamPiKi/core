package com.depromeet.piki.support

import com.depromeet.piki.auth.infrastructure.oauth.OAuthProvider
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@TestConfiguration(proxyBeanMethods = false)
class IntegrationStubs {
    @Bean
    @Primary
    fun linkSnapshotExtractor(): StubLinkSnapshotExtractor = StubLinkSnapshotExtractor()

    @Bean
    @Primary
    fun imageSnapshotExtractor(): StubImageSnapshotExtractor = StubImageSnapshotExtractor()

    @Bean
    @Primary
    fun imageStorage(): StubImageStorage = StubImageStorage()

    @Bean
    @Primary
    fun extractionModelProbe(): StubExtractionModelProbe = StubExtractionModelProbe()

    // 운영 FirebaseMessageSender 는 키가 없는 테스트 환경에서 안 뜨지만, ObjectProvider 로 찾는 쪽이 있어 stub 이 필요함
    @Bean
    @Primary
    fun fcmMessageSender(): StubFcmMessageSender = StubFcmMessageSender()

    @Bean
    @Primary
    fun refreshTokenStore(): StubRefreshTokenStore = StubRefreshTokenStore()

    @Bean
    @Primary
    fun withdrawnTokenStore(): StubWithdrawnTokenStore = StubWithdrawnTokenStore()

    // 아래 넷은 oauth.client.enabled=false 로 운영 빈이 꺼져 stub 이 유일한 후보라 @Primary 없음
    @Bean
    fun kakaoOAuthClient(): StubOAuthClient = StubOAuthClient(OAuthProvider.KAKAO)

    @Bean
    fun googleOAuthClient(): StubOAuthClient = StubOAuthClient(OAuthProvider.GOOGLE)

    @Bean
    fun appleOAuthClient(): StubOAuthClient = StubOAuthClient(OAuthProvider.APPLE)

    @Bean
    fun appleNotificationVerifier(): StubAppleNotificationVerifier = StubAppleNotificationVerifier()

    @Bean
    @Primary
    fun announcementImageFetcher(): StubAnnouncementImageFetcher = StubAnnouncementImageFetcher()

    @Bean
    @Primary
    fun discordMessageSender(): StubDiscordMessageSender = StubDiscordMessageSender()
}
