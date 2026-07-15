package com.depromeet.piki.common.openapi

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.HttpMappable
import com.depromeet.piki.common.response.ApiResponseBody
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.examples.Example
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import org.springframework.http.HttpStatus
import org.springframework.web.method.HandlerMethod
import tools.jackson.databind.ObjectMapper
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaMethod
import org.springframework.http.MediaType as SpringMediaType

fun HandlerMethod.binds(ref: KFunction<*>): Boolean {
    val target = ref.javaMethod ?: return false
    return method.declaringClass == target.declaringClass &&
        method.name == target.name &&
        method.parameterTypes.contentEquals(target.parameterTypes)
}

fun Operation.examples(
    objectMapper: ObjectMapper,
    block: OperationExamples.() -> Unit,
) {
    OperationExamples(this, objectMapper).block()
}

class OperationExamples(
    private val operation: Operation,
    private val objectMapper: ObjectMapper,
) {
    fun add(
        status: HttpStatus,
        name: String,
        payload: Any,
    ) {
        val response = operation.responses?.get(status.value().toString()) ?: return
        val content = response.content ?: Content().also { response.content = it }
        val mediaType =
            content[SpringMediaType.APPLICATION_JSON_VALUE]
                ?: MediaType().also { content[SpringMediaType.APPLICATION_JSON_VALUE] = it }
        val example = Example().value(objectMapper.convertValue(payload, Any::class.java))
        mediaType.examples = (mediaType.examples ?: linkedMapOf()).apply { put(name, example) }
    }

    // HttpMappable 도메인 예외를 직접 받아 status·category·detail 을 예외 정의 한 곳에서 끌어온다.
    // GlobalExceptionHandler.handleBaseException 과 동일한 변환(status=httpStatus, body=fail(category, message))이라
    // example 이 실제 응답과 자동 동기화되고, 예외 팩토리의 message·category·status·시그니처가 바뀌면
    // example 도 따라가거나 컴파일 에러로 드러난다. detail 문자열을 손으로 복붙하던 패턴을 대체한다.
    fun <E> add(
        exception: E,
        name: String,
    ) where E : BaseException, E : HttpMappable =
        add(
            status = exception.httpStatus,
            name = name,
            // code 를 배정한 예외는 example 에도 code 를 실어 문서와 실제 응답을 일치시킨다.
            // 이관 전 예외(errorCode=null)는 기존대로 code 없이 category fallback.
            payload =
                exception.errorCode
                    ?.let { ApiResponseBody.fail<Unit>(it, exception.message) }
                    ?: ApiResponseBody.fail<Unit>(category = exception.category, detail = exception.message),
        )

    // Security 필터 단(ApiResponseSecurityErrorHandlers)이 내려보내는 401/403 은 detail 없이 fail(category) 라
    // 전 엔드포인트에 같은 example 이 반복된다. 그 보일러플레이트를 한 자리로 모은다.
    // (도메인 예외 403 처럼 detail 이 있는 응답은 그대로 add 를 직접 쓴다.)
    fun unauthorized(name: String = "인증 필요") =
        add(HttpStatus.UNAUTHORIZED, name, ApiResponseBody.fail<Unit>(category = ErrorCategory.UNAUTHORIZED))

    fun forbidden(name: String) =
        add(HttpStatus.FORBIDDEN, name, ApiResponseBody.fail<Unit>(category = ErrorCategory.FORBIDDEN))
}
