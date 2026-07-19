package com.depromeet.piki.common.exception

import com.depromeet.piki.common.response.ApiResponseBody
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

// ResponseEntityExceptionHandler 를 상속해 Spring 표준 MVC 예외(HttpMessageNotReadable·메서드 미지원·
// 미디어타입 미지원·필수 파라미터 누락·타입 불일치 등)를 올바른 4xx 로 처리한다.
// 상속 전에는 이들이 아래 catch-all `Exception` 에 먼저 잡혀 전부 500 으로 샜다 — 클라이언트 입력 실수가
// 서버 오류로 응답되던 전역 갭(#300). RESEH 의 표준 핸들러가 status 를 정하고, handleExceptionInternal
// override 가 응답 바디만 우리 ApiResponseBody 포맷으로 통일한다. catch-all 은 예상 못한 서버 버그만 500.
@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BaseException::class)
    fun handleBaseException(e: BaseException): ResponseEntity<ApiResponseBody<Nothing>> {
        val status = if (e is HttpMappable) e.httpStatus else HttpStatus.INTERNAL_SERVER_ERROR
        val category = if (e is HttpMappable) e.category else ErrorCategory.SERVER_ERROR
        // 5xx 레벨은 HttpMappable 유무가 아니라 category 로 가른다 — 같은 502 라도 SERVER_ERROR(우리 설정·코드 버그:
        // OAuth misconfigured·Gemini clientError)와 RETRYABLE(외부 일시 장애: provider 호출·Gemini callFailed)은
        // 심각도가 다르다. HttpMappable 5xx 를 전부 warn 으로 묶으면 INTERNAL_SERVER_ERROR+SERVER_ERROR 인
        // nicknameGenerationFailed 같은 진짜 서버 버그가 알림에서 누락된다.
        when {
            // SERVER_ERROR(500/502) = 재시도해도 무의미한 우리 서버 문제 → error + 스택(알림 신호).
            // HttpMappable 아닌 BaseException 도 category 가 SERVER_ERROR 라 여기로 와 스택과 함께 남는다.
            status.is5xxServerError && category == ErrorCategory.SERVER_ERROR ->
                log.error("[{}] {} -> {}", e.javaClass.simpleName, e.message, status.value(), e)
            // RETRYABLE 5xx(502) = 외부 의존성 일시 실패 → warn, cause 추적 위해 예외 동봉. 클라는 재시도로 대응 가능.
            status.is5xxServerError ->
                log.warn("[{}] {} -> {}", e.javaClass.simpleName, e.message, status.value(), e)
            // 4xx = 클라이언트 계약 위반 → info. 서버 입장에선 정상 거부다.
            else ->
                log.info("[{}] {} -> {}", e.javaClass.simpleName, e.message, status.value())
        }
        // code 우선순위: (1) 예외 자신이 배정한 errorCode → 그대로. (2) 없으면 category 로 공통 code 를 파생 —
        // 도메인 code 를 아직 안 단 5xx(외부 의존성 실패 등)를 재시도 방식별 공통 5xx code(RETRYABLE/SERVER_ERROR)로
        // 뭉치고, CONFLICT 처럼 공통 code 가 없는 category 는 null → 기존 fail(category) fallback(code 없음).
        // detail 은 어느 경로든 e.message 를 유지한다(이관 단계: code 만 추가, detail 제거는 전체 이관 후).
        val errorCode = (e as? HttpMappable)?.errorCode ?: CommonErrorCode.of(category)
        val body =
            errorCode
                ?.let { ApiResponseBody.fail<Nothing>(it, e.message) }
                ?: ApiResponseBody.fail(category, e.message)
        return ResponseEntity
            .status(status)
            .body(body)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<ApiResponseBody<Nothing>> {
        log.info("[IllegalArgumentException] {}", e.message)
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ApiResponseBody.fail(CommonErrorCode.INVALID_INPUT, e.message))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ApiResponseBody<Nothing>> {
        log.error("[UnexpectedException] {}", e.message, e)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponseBody.fail(CommonErrorCode.SERVER_ERROR))
    }

    // RESEH 의 모든 표준 예외 핸들러가 최종적으로 이 메서드를 거쳐 응답 바디를 만든다 → ApiResponseBody 로 통일.
    // status 는 RESEH 가 정한 값을 그대로 쓰고(예: HttpMessageNotReadable→400, 메서드 미지원→405, 미디어타입→415),
    // 바디만 우리 래퍼로 교체한다.
    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val status = HttpStatus.valueOf(statusCode.value())
        // 5xx 는 서버 버그 신호이므로 스택 트레이스와 함께 error 로, 4xx(클라 계약 위반)는 info 로 남긴다.
        if (status.is5xxServerError) {
            log.error("[{}] {}", ex.javaClass.simpleName, ex.message, ex)
        } else {
            log.info("[{}] {} → {}", ex.javaClass.simpleName, ex.message, status.value())
        }
        // category 로 공통 code 를 파생해 표준 MVC 4xx(400/404/405/415)에도 code 를 싣는다. detail 은 detailOf(ex)
        // 를 유지(이관 단계: code 만 추가). 공통 code 가 없는 category(CONFLICT 등)는 null → 기존 fail(category) fallback.
        val category = categoryOf(status)
        val commonCode = CommonErrorCode.of(category)
        val wrapped: ApiResponseBody<Nothing> =
            commonCode
                ?.let { ApiResponseBody.fail(it, detailOf(ex)) }
                ?: ApiResponseBody.fail(category, detailOf(ex))
        return ResponseEntity.status(statusCode).headers(headers).body(wrapped)
    }

    // status → 우리 ErrorCategory. 인증/권한/리소스/충돌·메서드·미디어타입은 각자, 그 외 4xx 는 입력 오류.
    // 5xx 는 재시도 방식별로 가른다 — RESEH 경로로 502/503 이 들어오면(AsyncRequestTimeout→503,
    // ResponseStatusException(502/503) 등) status 와 code 가 어긋나지 않게 RETRYABLE/SERVER_BUSY 로,
    // 그 외 5xx(500 등)는 SERVER_ERROR 로. internal — categoryOf 매핑 단위 검증에 열어둔다.
    internal fun categoryOf(status: HttpStatus): ErrorCategory =
        when {
            status == HttpStatus.UNAUTHORIZED -> ErrorCategory.UNAUTHORIZED
            status == HttpStatus.FORBIDDEN -> ErrorCategory.FORBIDDEN
            status == HttpStatus.NOT_FOUND -> ErrorCategory.NOT_FOUND
            status == HttpStatus.METHOD_NOT_ALLOWED -> ErrorCategory.METHOD_NOT_ALLOWED
            status == HttpStatus.UNSUPPORTED_MEDIA_TYPE -> ErrorCategory.UNSUPPORTED_MEDIA_TYPE
            status == HttpStatus.CONFLICT -> ErrorCategory.CONFLICT
            status.is4xxClientError -> ErrorCategory.INVALID_INPUT
            status == HttpStatus.BAD_GATEWAY -> ErrorCategory.RETRYABLE
            status == HttpStatus.SERVICE_UNAVAILABLE -> ErrorCategory.SERVER_BUSY
            else -> ErrorCategory.SERVER_ERROR
        }

    // 검증 실패만 첫 위반 필드의 메시지를 노출해 사용자 수정을 돕는다. 필드명(영문 식별자) 접두사는 내부 용어라
    // 붙이지 않고 메시지만 내려보낸다. 그 외 표준 예외는 내부 메시지(파서 세부 등)를 노출하지 않도록 null 로 두어,
    // fail 이 category 의 고정 문구로 응답하게 한다(detail 노이즈·정보 누출 방지).
    private fun detailOf(ex: Exception): String? =
        when (ex) {
            is MethodArgumentNotValidException ->
                ex.bindingResult.fieldErrors.firstOrNull()
                    ?.let { it.defaultMessage ?: "다시 한번 확인해 주세요." }
                    ?: "다시 한번 확인해 주세요."
            else -> null
        }
}
