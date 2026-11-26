package com.depromeet.piki.common.exception

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// nginx 가 앱 앞에서 끊는 413 은 GlobalExceptionHandler 를 못 거쳐, 응답 JSON 을 설정 파일에 손으로 박는다.
// 그래서 앱 문구가 바뀌어도 컴파일러가 못 잡는 drift 가 생긴다 — 설정 주석이 "손으로 맞춰야 한다" 고
// 예고하던 바로 그 틈이고, 실제로 벌어져 있었다(#1268 리뷰). 판정이 문자열 비교 하나로 끝나므로
// 산문 경고 대신 테스트로 내린다.
//
// 비교 대상이 ErrorCategory.INVALID_INPUT 인 이유: 앱은 MaxUploadSizeExceededException 전용 핸들러가 없어
// ResponseEntityExceptionHandler 경로로 가고, 거기서 detailOf() 가 null 을 돌려줘 category 고정 문구가 실린다.
// 이름이 비슷한 CommonErrorCode.INVALID_INPUT 의 문구("요청 값을 다시 확인해 주세요.")와 값이 다르므로,
// 그쪽과 맞추면 사용자가 같은 상황에서 다른 안내를 본다.
class NginxErrorPayloadTest {
    @Test
    fun `nginx 413 응답의 code 와 detail 은 앱이 내는 값과 같다`() {
        nginxConfigs().forEach { path ->
            val payload = bodyTooLargePayloadOf(path)
            assertEquals(
                CommonErrorCode.INVALID_INPUT.code,
                jsonFieldOf(payload, "code"),
                "$path 의 413 code 가 앱과 어긋난다",
            )
            assertEquals(
                ErrorCategory.INVALID_INPUT.description,
                jsonFieldOf(payload, "detail"),
                "$path 의 413 detail 이 앱과 어긋난다. 비교 대상은 ErrorCategory 쪽이다(CommonErrorCode 아님)",
            )
        }
    }

    // 설정 파일을 못 찾으면 위 단언이 빈 목록을 돌아 조용히 통과한다 — 그 무해한 통과를 막는다.
    @Test
    fun `검사 대상 nginx 설정 파일이 존재한다`() {
        val configs = nginxConfigs()
        assertEquals(2, configs.size, "prod·dev 두 설정을 모두 검사해야 한다")
        configs.forEach { assertTrue(Files.exists(it), "$it 가 없다") }
    }

    private fun nginxConfigs(): List<Path> =
        listOf(
            Path.of("infra/nginx/api.piki.day.conf"),
            Path.of("infra/nginx/dev.api.piki.day.conf"),
        )

    // @body_too_large 블록의 return 문에 박힌 JSON 리터럴만 꺼낸다 — 같은 파일의 429 블록과 섞이지 않게
    // 블록 이름 뒤에서부터 찾는다.
    //
    // 블록을 중괄호로 잘라내지 않는다 — JSON 리터럴 안에 중괄호가 있어 첫 `}` 로 자르면 payload 중간이 날아간다.
    private fun bodyTooLargePayloadOf(path: Path): String {
        val afterMarker = Files.readString(path).substringAfter("location @body_too_large")
        return Regex("return 413 '([^']+)'").find(afterMarker)?.groupValues?.get(1)
            ?: error("$path 에서 413 응답 JSON 을 찾지 못했다 — 블록 모양이 바뀌었으면 이 테스트도 함께 고친다")
    }

    private fun jsonFieldOf(
        payload: String,
        field: String,
    ): String =
        Regex("\"$field\":\"([^\"]*)\"").find(payload)?.groupValues?.get(1)
            ?: error("$field 를 찾지 못했다: $payload")
}
