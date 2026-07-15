package com.depromeet.piki.auth.web

import com.depromeet.piki.common.response.ApiResponseBody
import org.slf4j.LoggerFactory
import org.springframework.core.MethodParameter
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseCookie
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice

// 토큰 전달의 cross-cutting 처리. 컨트롤러는 TokenCarrying/TokenClearing DTO 를 반환할 뿐이고,
// 쿠키 set/expire 는 여기서 타입 매칭으로 일어난다 (문자열 sniffing 없음).
//
// secure by default — 기본은 WEB(쿠키)이고, body 토큰은 X-Client-Type: app 을 명시한 경우에만 내준다.
// - WEB(기본·web 명시·미상): TokenCarrying → 쿠키 set + body 토큰 제거 / TokenClearing → 만료 쿠키
// - APP(app 명시): 쿠키 미사용, body 그대로
@RestControllerAdvice
class TokenCookieResponseAdvice(
    private val tokenCookieWriter: TokenCookieWriter,
) : ResponseBodyAdvice<Any> {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun supports(
        returnType: MethodParameter,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Boolean = true

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse,
    ): Any? {
        val responseBody = body as? ApiResponseBody<*> ?: return body
        return when (val data = responseBody.data) {
            is TokenCarrying -> handleTokenIssue(responseBody, data, request, response)
            is TokenClearing -> {
                handleTokenClear(response)
                body
            }
            else -> body
        }
    }

    // 토큰 발급: WEB(기본)은 쿠키로 내리고 body 토큰을 비운다. app 명시일 때만 body 그대로.
    private fun handleTokenIssue(
        responseBody: ApiResponseBody<*>,
        data: TokenCarrying,
        request: ServerHttpRequest,
        response: ServerHttpResponse,
    ): Any? {
        val header = request.headers.getFirst(ClientType.HEADER)
        val web = ClientType.from(header) == ClientType.WEB
        // 토큰 값은 절대 로깅하지 않는다. 판별 신호(헤더 값)만 남긴다.
        log.info(
            "토큰 발급 응답: clientType={} (X-Client-Type={})",
            if (web) ClientType.WEB else ClientType.APP,
            header ?: "none",
        )
        if (!web) return responseBody
        writeCookies(response, tokenCookieWriter.setCookies(data.tokenPair))
        return stripBodyTokens(responseBody, data)
    }

    // 로그아웃: 쿠키 만료는 ClientType 무관하게 무조건 내린다. 클라가 X-Client-Type 을 빠뜨려도
    // 쿠키가 확실히 삭제되도록(fail-safe — 안 지우면 access 쿠키가 만료까지 인증에 쓰임).
    // APP 은 쿠키 jar 가 없어 만료 쿠키를 받아도 무해하다.
    private fun handleTokenClear(response: ServerHttpResponse) {
        log.info("토큰 쿠키 만료 (logout)")
        writeCookies(response, tokenCookieWriter.clearCookies())
    }

    private fun writeCookies(
        response: ServerHttpResponse,
        cookies: List<ResponseCookie>,
    ) {
        cookies.forEach { response.headers.add(HttpHeaders.SET_COOKIE, it.toString()) }
    }

    private fun stripBodyTokens(
        original: ApiResponseBody<*>,
        data: TokenCarrying,
    ): ApiResponseBody<*> =
        ApiResponseBody(
            data = data.withoutBodyTokens(),
            code = original.code,
            detail = original.detail,
            pageResponse = original.pageResponse,
        )
}
