package com.depromeet.piki.common.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.session.web.http.CookieSerializer
import org.springframework.session.web.http.DefaultCookieSerializer

/**
 * 세션 쿠키(SESSION)의 보안 속성을 코드로 확정한다(#885).
 *
 * 세션을 Redis 로 옮기면서(Spring Session) 세션 쿠키 발급 주체가 톰캣에서 Spring Session 의 CookieSerializer 로
 * 바뀐다. 그런데 Boot 의 세션 auto-configuration 이 만드는 기본 serializer 는 이 앱 컨텍스트에서
 * `useSecureCookie=false` · `useHttpOnlyCookie=false` · `sameSite=null` 로 앉는다(실측). 즉 그냥 두면 톰캣
 * JSESSIONID 가 기본으로 갖던 HttpOnly 조차 잃은 채 쿠키가 나간다. 그래서 정책을 auto-config 의 프로퍼티 매핑에
 * 맡기지 않고 여기서 명시한다 — 이 빈이 있으면 auto-config 는 자기 serializer 를 만들지 않는다.
 *
 * 쿠키 정책을 한 곳에 캡슐화하는 것은 이 repo 가 JWT 쿠키에 이미 쓰는 방식이다(auth/web/TokenCookieWriter).
 * 세션 쿠키만 프레임워크가 자동 발급한다는 이유로 그 정책 밖에 있었고, 그래서 Secure 가 빠진 채였다.
 *
 * - **Secure**: `request.isSecure()` 에 기대지 않고 설정값으로 박는다. nginx 가 X-Forwarded-Proto 를 보내지만
 *   `forward-headers-strategy` 가 없어 앱은 요청을 평문 HTTP 로 보기 때문이다. JWT 쿠키가 같은 이유로 쓰는
 *   `COOKIE_SECURE` 를 그대로 재사용해 "쿠키 Secure 정책은 이 노브 하나" 로 모은다(로컬 HTTP 는 false).
 * - **HttpOnly**: 항상 켠다. 세션 쿠키를 스크립트가 읽을 이유가 없다.
 * - **SameSite=Lax**: Discord grant 링크 클릭은 top-level GET 이라 Lax 로 쿠키가 실린다. None 으로 넓히지 않는다.
 */
@Configuration
class SessionCookieConfig {
    @Bean
    fun sessionCookieSerializer(
        @Value("\${server.servlet.session.cookie.secure:true}") secure: Boolean,
    ): CookieSerializer =
        DefaultCookieSerializer().apply {
            setUseSecureCookie(secure)
            setUseHttpOnlyCookie(true)
            setSameSite("Lax")
        }
}
