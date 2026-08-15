package com.depromeet.piki.item.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// 파싱 워커의 포획 범위를 고정한다(#941). 표준 runCatching 은 Throwable 을 전부 잡아 Error 까지 삼키는데,
// 그러면 치명 오류를 받아 처리하도록 만들어 둔 바깥 층(JVM 종료·컨테이너 재시작·헬스체크)이 작동하지 못한다.
// 여기서 잡히는 것과 통과하는 것의 경계를 못 박아, 다시 넓은 포획으로 되돌아가면 깨지게 한다.
class RunCatchingExceptionTest {
    @Test
    fun `정상 반환값은 성공 Result 로 감싼다`() {
        val result = runCatchingException { 42 }

        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `Exception 은 잡아 실패 Result 로 만든다`() {
        // 파싱 실패의 대부분 — 추출 실패·전이 거부·DB 오류. 워커가 분류해 재시도·종결을 정한다.
        val result = runCatchingException { throw IllegalStateException("boom") }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `치명적 JVM 오류(Error)는 잡지 않고 그대로 전파한다`() {
        // 이 테스트가 이 파일의 존재 이유다. Error 를 잡으면 워커가 그것을 "확정 실패" 로 분류해 종결하고,
        // 이미지 경로에서는 raw 원본까지 회수해 재실행할 입력이 사라진다.
        assertFailsWith<OutOfMemoryError> { runCatchingException { throw OutOfMemoryError() } }
        assertFailsWith<StackOverflowError> { runCatchingException { throw StackOverflowError() } }
        assertFailsWith<NoClassDefFoundError> { runCatchingException { throw NoClassDefFoundError() } }
    }

    @Test
    fun `파싱 워커는 표준 runCatching 을 쓰지 않는다`() {
        // 위 포획 범위는 워커가 이 함수를 쓸 때만 의미가 있다. 한 곳이라도 표준 runCatching 으로 되돌아가면
        // 그 자리에서만 Error 가 다시 삼켜지는데, 그건 리뷰로만 걸러야 해서 조용히 새기 쉽다 — 기계로 못 박는다.
        val workers =
            listOf(
                "AsyncItemParsingWorker.kt",
                "AsyncImageParsingWorker.kt",
            ).map { java.io.File("src/main/kotlin/com/depromeet/piki/item/service/$it") }

        val offenders =
            workers.filter { file ->
                file.readLines().any { line ->
                    // 주석이 아닌 실제 호출만 본다 — 이 규칙을 설명하는 주석에 이름이 등장하는 것은 위반이 아니다.
                    val code = line.substringBefore("//")
                    Regex("""(^|[^a-zA-Z])runCatching\s*\{""").containsMatchIn(code)
                }
            }

        assertTrue(
            offenders.isEmpty(),
            "표준 runCatching 은 Error 까지 삼킨다. runCatchingException 으로 바꿔라: ${offenders.map { it.name }}",
        )
    }
}
