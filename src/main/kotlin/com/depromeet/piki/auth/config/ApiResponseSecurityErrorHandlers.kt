package com.depromeet.piki.auth.config

import com.depromeet.piki.common.exception.CommonErrorCode
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.response.ApiResponseBody
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

// Security 필터 체인은 DispatcherServlet 이전 단계라 @RestControllerAdvice 의 GlobalExceptionHandler 가
// 잡지 못한다. 인증·인가 거부 응답도 다른 엔드포인트와 같은 ApiResponseBody contract 로 내려가도록
// EntryPoint·AccessDeniedHandler 를 여기에 직접 구현한다.
//
// detail 은 ErrorCategory.description 의 고정 사용자 대면 문구로 채워, 토큰 파싱 사유 등 내부 정보가
// 응답으로 새지 않게 한다 (CLAUDE.md 의 "클라이언트 응답에 내부 정보 노출 안 함" 정책).

@Component
class ApiResponseAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        // 미인증 401 은 클라이언트 계약 위반이라 INFO 로 남긴다(로깅 정책). 단 우리 API 표면(/api/**)에 한정한다.
        // 우리가 인증을 요구하는 경로는 전부 /api/v1/** 라, 정상 클라이언트가 비-API 경로로 401 을 받을 일이 없다.
        // 비-API 401 은 루트·favicon·인터넷 스캐너 probe(/test/.git/config·/.env 등)가 만드는 노이즈일 뿐이다.
        // 비-API 는 평소 안 보이게 DEBUG 로 강등한다(drop 아님). 조사 필요시 actuator/loggers 로 이 로거를
        // DEBUG 로 올리면 nginx 차단된 박스 안에서 다시 볼 수 있다. 어느 쪽이든 거절(401 응답)은 그대로이고 보안 경계는 바뀌지 않는다.
        if (request.requestURI.startsWith(API_PATH_PREFIX)) {
            log.info(ENTRY_POINT_LOG, request.method, request.requestURI, authException.message)
        } else {
            log.debug(ENTRY_POINT_LOG, request.method, request.requestURI, authException.message)
        }
        writeApiResponseBody(response, CommonErrorCode.UNAUTHORIZED, objectMapper)
    }

    private companion object {
        const val API_PATH_PREFIX = "/api/"
        const val ENTRY_POINT_LOG = "[AuthenticationEntryPoint] {} {} - {}"
    }
}

@Component
class ApiResponseAccessDeniedHandler(
    private val objectMapper: ObjectMapper,
) : AccessDeniedHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        log.info("[AccessDeniedHandler] {} {} - {}", request.method, request.requestURI, accessDeniedException.message)
        writeApiResponseBody(response, CommonErrorCode.FORBIDDEN, objectMapper)
    }
}

// status 는 errorCode.category.httpStatus 로 파생하고 body 에 code 를 실어, GlobalExceptionHandler 를 못 거치는
// 필터 단 401/403 도 다른 엔드포인트와 같은 code 기반 contract 로 내려간다. detail 은 미지정 →
// category.description 고정 문구가 그대로 나가 이관 전 동작을 보존한다(code 만 추가).
private fun writeApiResponseBody(
    response: HttpServletResponse,
    errorCode: ErrorCode,
    objectMapper: ObjectMapper,
) {
    response.status = errorCode.category.httpStatus.value()
    response.contentType = MediaType.APPLICATION_JSON_VALUE
    response.characterEncoding = Charsets.UTF_8.name()
    val body = ApiResponseBody.fail<Nothing>(errorCode)
    response.writer.write(objectMapper.writeValueAsString(body))
}
