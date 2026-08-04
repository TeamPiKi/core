package com.depromeet.piki.admin.config

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

class AdminHeaderInterceptorTest {
    // localBypass 가 켜지면 프로파일과 무관하게 LOCAL. 그 외엔 dev 프로파일이면 DEV, 아니면 PROD.
    @ParameterizedTest(name = "localBypass={0}, isDev={1} -> {2}")
    @CsvSource(
        "true,  false, LOCAL", // 로컬 (localBypass 가 최우선)
        "true,  true,  LOCAL", // 로컬은 다른 신호를 가린다
        "false, true,  DEV", // dev 프로파일
        "false, false, PROD", // prod 프로파일
    )
    fun `환경 판정은 localBypass→dev프로파일 순으로 LOCAL·DEV·PROD 를 가른다`(
        localBypass: Boolean,
        isDevProfile: Boolean,
        expected: String,
    ) {
        val env = AdminHeaderInterceptor.resolveEnv(localBypass, isDevProfile)

        assertEquals(expected, env)
    }
}
