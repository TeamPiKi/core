package com.depromeet.piki.common.response

import com.depromeet.piki.user.domain.UserException
import org.springframework.http.HttpStatus
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// "code 필드는 String, enum 은 fail() 경계" 설계가 실제 wire 로 무엇을 내보내는지 못 박는 유닛 테스트.
// Spring·Docker 없이 순수 Jackson 직렬화만 검증한다.
class ApiResponseBodyCodeTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `fail(errorCode) 의 code 는 enum 이름이 아니라 문자열 USER-001 로 직렬화된다`() {
        val e = UserException.notFound()

        val json = mapper.writeValueAsString(ApiResponseBody.fail<Unit>(e.errorCode, e.message))

        assertTrue(json.contains("\"code\":\"USER-001\""), "실제 직렬화: $json")
        // enum 을 필드로 박았다면 Jackson 이 name()="NOT_FOUND" 를 뱉었을 것 — 그게 아님을 증명.
        assertFalse(json.contains("NOT_FOUND"), "enum name 이 새어나오면 안 됨: $json")
        assertTrue(json.contains("\"detail\":\"존재하지 않는 계정이에요.\""), "실제 직렬화: $json")
    }

    @Test
    fun `UserException 은 errorCode 를 들고 status·category 를 code 에서 파생한다`() {
        val e = UserException.notFound()

        assertEquals("USER-001", e.errorCode.code)
        assertEquals(HttpStatus.NOT_FOUND, e.httpStatus) // category.httpStatus 로 파생
    }

    @Test
    fun `성공 응답의 code 는 null 로 직렬화된다 (에러 전용 code)`() {
        val json = mapper.writeValueAsString(ApiResponseBody.ok<Unit>())

        assertTrue(json.contains("\"code\":null"), "실제 직렬화: $json")
    }

    @Test
    fun `구 invalidNickname 은 blank·tooLong·reserved 3코드로 분리되어 서로 다른 code 를 갖는다`() {
        assertEquals("USER-006", UserException.nicknameBlank().errorCode.code)
        assertEquals("USER-012", UserException.nicknameTooLong().errorCode.code)
        assertEquals("USER-013", UserException.nicknameReserved().errorCode.code)
    }

    @Test
    fun `fail(errorCode) 에 detail 을 안 넘기면 category 의 fallback 문구가 detail 이 된다`() {
        val json = mapper.writeValueAsString(ApiResponseBody.fail<Unit>(UserException.notFound().errorCode))

        // detail fallback = ErrorCategory.NOT_FOUND.description
        assertTrue(json.contains("\"code\":\"USER-001\""), "실제 직렬화: $json")
        assertTrue(json.contains("\"detail\":\"요청하신 정보를 찾을 수 없어요.\""), "실제 직렬화: $json")
    }
}
