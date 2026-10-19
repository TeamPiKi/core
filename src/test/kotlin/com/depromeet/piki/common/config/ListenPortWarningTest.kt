package com.depromeet.piki.common.config

import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.mock.env.MockEnvironment
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(OutputCaptureExtension::class)
class ListenPortWarningTest {
    @Test
    fun `PORT env 없이 뜨면 기본 포트로 리슨한다는 경고를 남긴다`(output: CapturedOutput) {
        val environment = MockEnvironment().withProperty("server.port", "1234")

        ListenPortWarning(environment).warnIfDefaultPort()

        assertTrue(output.out.contains("PORT env 없음 - 기본 포트 1234 로 리슨"))
    }

    @Test
    fun `PORT env 를 받으면 경고하지 않는다`(output: CapturedOutput) {
        val environment = MockEnvironment().withProperty("PORT", "1234")

        ListenPortWarning(environment).warnIfDefaultPort()

        assertFalse(output.out.contains("PORT env 없음"))
    }
}
