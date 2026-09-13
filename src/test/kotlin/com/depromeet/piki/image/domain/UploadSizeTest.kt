package com.depromeet.piki.image.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UploadSizeTest {
    @ParameterizedTest
    @ValueSource(longs = [1L, 1_024L, 5L * 1024 * 1024])
    fun `1 바이트 이상 상한 이하면 그대로 생성된다`(contentLength: Long) {
        assertEquals(contentLength, UploadSize.of(contentLength).bytes)
    }

    @Test
    fun `상한을 1 바이트라도 넘으면 tooLarge 로 거부된다`() {
        val e = assertFailsWith<ImageUploadException> { UploadSize.of(UploadSize.MAX_BYTES + 1) }

        assertEquals(ImageUploadErrorCode.TOO_LARGE, e.errorCode)
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, -1L, Long.MIN_VALUE])
    fun `0 이하는 크기로 성립하지 않아 거부된다`(contentLength: Long) {
        val e = assertFailsWith<ImageUploadException> { UploadSize.of(contentLength) }

        assertEquals(ImageUploadErrorCode.INVALID_SIZE, e.errorCode)
    }

    @Test
    fun `미지정은 크기를 알 수 없는 요청으로 거부된다`() {
        val e = assertFailsWith<ImageUploadException> { UploadSize.of(null) }

        assertEquals(ImageUploadErrorCode.INVALID_SIZE, e.errorCode)
    }
}
