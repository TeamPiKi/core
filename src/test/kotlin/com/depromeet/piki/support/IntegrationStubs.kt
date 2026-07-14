package com.depromeet.piki.support

import com.depromeet.piki.auth.infrastructure.oauth.OAuthProvider
import com.depromeet.piki.auth.infrastructure.redis.RefreshTokenStore
import com.depromeet.piki.item.service.AsyncImageParsingWorker
import com.depromeet.piki.item.service.AsyncItemParsingWorker
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

// 통합 테스트의 외부 호출 stub 빈을 한 곳에 모은다.
// IntegrationTestSupport 가 import 하므로 모든 통합 테스트가 같은 컨텍스트를 공유한다.
// 클래스별 @TestConfiguration / @Import 로 컨텍스트를 분기하면 캐시 적중률이 떨어지므로 금지.
//
// 각 stub 은 운영 @Component 빈(HttpProductLinkExtractor·HttpImageSnapshotExtractor 등)과 타입이 같아
// 주입 후보가 2개가 된다. @Primary 로 stub 우선을 명시한다 — 빈 이름과 주입 지점
// 파라미터명이 우연히 일치하는 데 기대지 않으므로, 파라미터명을 리팩터링해도 격리가 깨지지 않는다.
@TestConfiguration(proxyBeanMethods = false)
class IntegrationStubs {
    // 파싱(링크·이미지)의 외부 경계는 원격 extractor HTTP 호출 하나다 — 두 진입점 인터페이스를 stub 해
    // 통합 테스트가 실제 원격 호출 없이 파싱 결과를 제어한다. 원격 클라이언트 자체(3갈래 번역·계약 가드)는
    // 단위(HttpProductLinkExtractorTest·HttpImageSnapshotExtractorTest)가 검증한다.
    @Bean
    @Primary
    fun productLinkExtractor(): StubProductLinkExtractor = StubProductLinkExtractor()

    @Bean
    @Primary
    fun imageSnapshotExtractor(): StubImageSnapshotExtractor = StubImageSnapshotExtractor()

    @Bean
    @Primary
    fun imageStorage(): StubImageStorage = StubImageStorage()

    // FCM 발송 외부 경계(#245). 운영 FirebaseMessageSender 는 FirebaseApp 키가 없는 테스트 환경에선
    // 아예 안 뜨지만, PushNotificationChannel 이 ObjectProvider 로 FcmMessageSender 를 찾으므로
    // stub 을 @Primary 로 등록해 발송 fan-out·죽은 토큰 정리를 실제 FCM 호출 없이 검증한다.
    @Bean
    @Primary
    fun fcmMessageSender(): StubFcmMessageSender = StubFcmMessageSender()

    // ItemParsingWorker·ImageParsingWorker 는 내부 비동기 워커를 래핑한 configurable stub.
    // enabled=true (기본): 실제 워커로 위임 — WishlistRegisterAsyncIntegrationTest 는 이 경로를 사용한다.
    // enabled=false: no-op — @Transactional 통합 테스트에서 미커밋 item 접근으로 발생하는 warn 로그 노이즈를
    //   없애려면 테스트 본문에서 false 로 설정한다(설정한 테스트가 직접 복원한다).
    @Bean
    @Primary
    fun itemParsingWorker(asyncItemParsingWorker: AsyncItemParsingWorker): StubItemParsingWorker =
        StubItemParsingWorker(asyncItemParsingWorker)

    @Bean
    @Primary
    fun imageParsingWorker(asyncImageParsingWorker: AsyncImageParsingWorker): StubImageParsingWorker =
        StubImageParsingWorker(asyncImageParsingWorker)

    @Bean
    @Primary
    fun refreshTokenStore(): StubRefreshTokenStore = StubRefreshTokenStore()

    @Bean
    @Primary
    fun withdrawnTokenStore(): StubWithdrawnTokenStore = StubWithdrawnTokenStore()

    // oauth.client.enabled=false 로 운영 OAuthClientConfig 를 비활성화해 실제 외부 호출을 막는다.
    // stub 빈이 유일한 OAuthClient 라 @Primary 불필요.
    @Bean
    fun kakaoOAuthClient(): StubOAuthClient = StubOAuthClient(OAuthProvider.KAKAO)

    @Bean
    fun googleOAuthClient(): StubOAuthClient = StubOAuthClient(OAuthProvider.GOOGLE)

    @Bean
    fun appleOAuthClient(): StubOAuthClient = StubOAuthClient(OAuthProvider.APPLE)

    // Apple 서버-서버 알림 서명 검증(외부 JWKS 호출) 격리. 운영 AppleOAuthClient 가 AppleNotificationVerifier 를
    // 구현하지만 oauth.client.enabled=false 로 꺼지므로, 이 stub 이 유일한 AppleNotificationVerifier 라 @Primary 불필요.
    @Bean
    fun appleNotificationVerifier(): StubAppleNotificationVerifier = StubAppleNotificationVerifier()

    // 이미지 프록시 외부 HTTP 호출 경계. DefaultImageProxyFetcher 는 실제 외부 CDN 을 호출하므로
    // 통합 테스트에서 stub 으로 교체한다.
    @Bean
    @Primary
    fun imageProxyFetcher(): StubImageProxyFetcher = StubImageProxyFetcher()

    // 공지 본문 외부 이미지 fetch 외부 경계(#561 rehost). 실제 외부 URL 호출을 막고 시나리오별 응답을 주입한다.
    @Bean
    @Primary
    fun announcementImageFetcher(): StubAnnouncementImageFetcher = StubAnnouncementImageFetcher()

    // 주간 리포트 Discord 게시 외부 경계. 실제 Discord 호출을 막고 게시 payload 를 캡처한다.
    @Bean
    @Primary
    fun discordMessageSender(): StubDiscordMessageSender = StubDiscordMessageSender()
}
