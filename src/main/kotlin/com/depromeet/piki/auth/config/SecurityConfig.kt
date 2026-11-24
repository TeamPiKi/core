package com.depromeet.piki.auth.config

import com.depromeet.piki.auth.filter.JwtAuthenticationFilter
import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val authenticationEntryPoint: ApiResponseAuthenticationEntryPoint,
    private val accessDeniedHandler: ApiResponseAccessDeniedHandler,
) {
    // @Order(2): admin 백오피스 체인(AdminSecurityConfig, @Order(1))이 /admin/** 를 먼저 관할하고, 그 외 모든 요청을
    // 이 메인 JWT(stateless) 체인이 잡는다. admin 체인이 securityMatcher 로 자기 경로만 매칭하므로 이 체인은 그대로 catch-all.
    @Bean
    @Order(2)
    fun securityFilterChain(
        http: HttpSecurity,
        jwtAuthenticationFilter: JwtAuthenticationFilter,
    ): SecurityFilterChain =
        http
            // CorsConfigurationSource 빈을 사용해 CORS 를 Security 필터 단에서 처리한다.
            // 이걸 빼면 preflight(OPTIONS) 가 anyRequest().authenticated() 에 잡혀 401 이 된다.
            .cors { }
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    // SSE(text/event-stream) 같은 async 요청은 완료·타임아웃·에러로 끝날 때 컨테이너로 ASYNC 디스패치되어
                    // 보안 필터 체인을 한 번 더 탄다. 이 재진입 시점엔 SecurityContext 가 복원되지 않아 AuthorizationFilter 가
                    // Access Denied 를 던지는데, async 응답은 헤더가 이미 flush 되어 committed 라 ExceptionTranslationFilter 가
                    // 403 본문을 쓰지 못하고 "response is already committed" DispatcherServlet ERROR 로그만 남는다(무해하나 폭증).
                    // ASYNC 디스패치는 원 REQUEST 가 이미 인가를 통과한 요청의 후속이라(막혔으면 async 가 시작조차 안 됨)
                    // permitAll 이 새 보안 구멍을 만들지 않는다. 반대로 Spring Security 6 이 기본 활성화한 전 디스패치 인가 중
                    // FORWARD/ERROR(forward/include 우회 방어)는 그대로 두고, ASYNC 만 골라 연다.
                    // (전역 shouldFilterAllDispatcherTypes(false) 대신 ASYNC 한정 — 최소 권한.)
                    .dispatcherTypeMatchers(DispatcherType.ASYNC)
                    .permitAll()
                    // 배포 워크플로우의 health check (`curl http://localhost:$PORT/health`) 가 인증 없이
                    // 통과해야 한다. anyRequest().authenticated() 에 잡히면 401 응답 → 워크플로우 실패 →
                    // 신규 컨테이너 롤백 → 배포 차단으로 이어진다.
                    .requestMatchers(HttpMethod.GET, "/health")
                    .permitAll()
                    // actuator health/prometheus 는 EC2 내부의 Grafana Alloy 가 localhost 로
                    // 직접 scrape 한다 (nginx 미경유). 외부 도달은 nginx 가 /actuator/ 를 403 으로
                    // 차단(infra/nginx/...conf)하므로, 앱 레벨 permitAll + 네트워크 레벨 차단의 2층 구조다.
                    // metrics·env 등 나머지 actuator 엔드포인트는 application.yml 에서 애초에 노출하지 않는다.
                    .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/prometheus")
                    .permitAll()
                    // 런타임 로그 레벨 변경(POST /actuator/loggers/{logger}) — 평소 DEBUG 인 비-API 인증 로그를
                    // 조사 시 켜기 위해 노출한다. POST 로 동작을 바꾸는 WRITE 엔드포인트라 health/prometheus 보다
                    // 민감하나, 위와 같은 2층 차단(외부는 nginx 가 /actuator/ 403)으로 localhost(박스 SSH)에서만 도달한다.
                    .requestMatchers("/actuator/loggers", "/actuator/loggers/**")
                    .permitAll()
                    // 루트(/)는 우리 API 표면이 아닌데 anyRequest().authenticated() 에 걸려 401 +
                    // 인증실패 로그(노이즈)를 남기던 것을 막기 위해 인증 대상에서 제외한다.
                    // WebConfig 가 /docs/index.html 로 리다이렉트해 깔끔한 응답을 낸다.
                    // (/favicon.ico 는 아래 별도 permitAll 로 처리: 사이트 전역 정적 자산.)
                    .requestMatchers(HttpMethod.GET, "/")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/guest")
                    .permitAll()
                    // OAuth 인가 URL 생성 — state 발급 포함. 미인증으로 호출 (로그인 전 단계).
                    .requestMatchers(HttpMethod.GET, "/api/v1/auth/*/url")
                    .permitAll()
                    // 소셜 로그인 진입점 — 미인증으로 호출(게스트 토큰은 선택). 게스트 토큰을 보내면 필터가 principal 을 채워 게스트-연결로 동작.
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/login/*")
                    .permitAll()
                    // Apple 서버-서버 알림 — Apple 이 직접 호출하는 외부 진입점이라 우리 JWT 인증이 없다.
                    // 진위는 payload JWT 의 서명(Apple JWKS)으로 가린다(AppleNotificationVerifier). 위조는 401.
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/apple/notifications")
                    .permitAll()
                    // Apple 웹 OAuth form_post 콜백 브릿지(#430) — Apple 이 redirect_uri 로 직접 POST 하는 외부 진입점이라
                    // 우리 JWT 인증이 없다. 로그인은 하지 않고 code·state 를 프론트 공용 콜백으로 302 만 한다(state 검증은 이후 login API).
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/apple/callback")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/token/refresh")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/dev/users", "/api/v1/dev/users/*")
                    .permitAll()
                    // dev 전용 도구(@Profile("!prod"), 운영엔 라우트 없음)는 인증만 요구한다(GUEST·MEMBER 모두 통과).
                    // 과거 hasAuthority(GUEST) 가 회원을 403 으로 막았으나, 이 엔드포인트들(테스트 유저 생성·토큰 발급·FCM 푸시)은
                    // 호출자 신분과 무관하게 동작하고 게스트 토큰은 permitAll 로 누구나 발급받아 게스트 전용이 보안 이득도 없어,
                    // 회원만 불필요하게 막던 제한을 제거한다.
                    .requestMatchers("/api/v1/dev/**")
                    .authenticated()
                    // API 문서: Stoplight Elements UI (/docs/**, static resource) + OpenAPI spec
                    // (/v3/api-docs/**, springdoc 제공). Swagger UI 는 사용하지 않음.
                    .requestMatchers("/docs/**", "/v3/api-docs/**")
                    .permitAll()
                    // 파비콘 — 브라우저가 모든 페이지에서 자동 요청하는 사이트 전역 정적 자산.
                    // docs 등 어디서든 401 없이 뜨도록 permit 한다. 민감정보 없는 공개 파일.
                    .requestMatchers(HttpMethod.GET, "/favicon.ico")
                    .permitAll()
                    // 위시리스트는 회원 전용 — 인증만 요구한다(GUEST 도 통과). 게스트 거부(403)는 Security 권한이 아니라
                    // WishlistService 가 도메인 계약(WishException.guestCannotUseWishlist)으로 내린다 —
                    // Security 에서 MEMBER 만 허용하면 게스트가 권한 없음 403(detail 없음)으로 떨어져
                    // "위시리스트는 회원 전용" 이라는 구체 사유를 못 전달하기 때문. (회원 탈퇴 DELETE /users/me 와 같은 패턴)
                    .requestMatchers("/api/v1/wishlists/**")
                    .authenticated()
                    // 소셜 토너먼트 게스트 합류: 계정 없이 초대 코드 + 닉네임으로 가입과 동시에 참여
                    .requestMatchers(HttpMethod.POST, "/api/v1/tournaments/*/join/guest")
                    .permitAll()
                    // 링크 접근 미리보기: 미인증 상태에서 tournamentId 로 참여 전 정보 확인
                    .requestMatchers(HttpMethod.GET, "/api/v1/tournaments/*/invite-preview")
                    .permitAll()
                    // 코드 입력 미리보기: 미인증 상태에서 6자리 코드만으로 토너먼트 정보 확인
                    .requestMatchers(HttpMethod.GET, "/api/v1/tournaments/by-invite-code")
                    .permitAll()
                    // 다른 사람 프로필·공개 폴더 조회: 비로그인도 본다(#1290). 노출 판정은 서비스가 하고,
                    // 토큰이 있으면 principal 이 채워져 관계(친구·차단)가 반영된다
                    .requestMatchers(HttpMethod.GET, "/api/v1/profiles/*", "/api/v1/profiles/*/folders/*")
                    .permitAll()
                    // 탐색 조회: 비로그인도 본다(#1292). 토큰이 있으면 차단 관계와 내가 담은 상품이 반영된다
                    .requestMatchers(HttpMethod.GET, "/api/v1/explore/recent")
                    .permitAll()
                    // 플레이 링크 정보 조회: 미인증 상태에서 링크 진입 시 토너먼트 정보 확인
                    .requestMatchers(HttpMethod.GET, "/api/v1/tournaments/*/play-link-info")
                    .permitAll()
                    // 토너먼트 플레이는 GUEST 도 허용
                    .requestMatchers("/api/v1/tournaments/**")
                    .authenticated()
                    // 알림 실시간 구독(SSE) — 인증 유저가 자기 알림 스트림을 연다 (GUEST 포함).
                    .requestMatchers("/api/v1/notifications/**")
                    .authenticated()
                    // FCM 토큰 등록/해제 — 인증 유저가 자기 기기 토큰을 등록한다 (GUEST 포함, 토너먼트 푸시 대상).
                    .requestMatchers("/api/v1/fcm/**")
                    .authenticated()
                    // 닉네임 중복 체크: 게스트 참여 화면에서 JWT 없는 상태로 호출하므로 인증 불필요
                    .requestMatchers(HttpMethod.GET, "/api/v1/users/nickname/check")
                    .permitAll()
                    // /users/me 는 자기 프로필 조회/수정 — 게스트 포함 인증 필요
                    // PATCH 는 닉네임·프로필 이미지를 한 요청(multipart)으로 수정한다 (멤버/게스트 권한 차이 없음).
                    .requestMatchers(HttpMethod.GET, "/api/v1/users/me")
                    .authenticated()
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/users/me")
                    .authenticated()
                    // 회원 탈퇴 — 인증만 요구한다(GUEST 도 통과). 게스트 거부(403)는 Security 권한이 아니라
                    // WithdrawalService 가 도메인 계약(UserException.guestCannotWithdraw)으로 내린다 —
                    // Security 에서 MEMBER 만 허용하면 게스트가 권한 없음 403(detail 없음)으로 떨어져
                    // "게스트는 탈퇴 불가" 라는 구체 사유를 못 전달하기 때문.
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/users/me")
                    .authenticated()
                    .anyRequest()
                    .authenticated()
            }.exceptionHandling {
                // 미인증 요청은 401, 인증됐지만 권한 없으면 403.
                // Security 필터 체인은 DispatcherServlet 이전이라 GlobalExceptionHandler 가 잡지 못하므로,
                // 두 경로 모두 ApiResponseBody contract 로 응답을 직접 작성하는 핸들러를 꽂는다.
                it.authenticationEntryPoint(authenticationEntryPoint)
                it.accessDeniedHandler(accessDeniedHandler)
            }.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .build()
}
