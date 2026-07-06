package com.depromeet.piki.image.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProductImageTest {
    @ParameterizedTest
    @ValueSource(strings = ["image/png", "image/jpeg", "image/webp", "image/heic", "image/heif"])
    fun `지원하는 MIME 타입이면 ProductImage 가 생성된다`(mimeType: String) {
        val bytes = byteArrayOf(1, 2, 3)

        val image = ProductImage.of(bytes, mimeType)

        assertEquals(mimeType, image.mimeType)
        assertContentEquals(bytes, image.bytes)
    }

    @ParameterizedTest
    @ValueSource(strings = ["IMAGE/JPEG", "Image/Png", "image/jpeg; charset=utf-8", "  image/webp  "])
    fun `대소문자·파라미터가 섞인 MIME 타입도 정규화되어 생성된다`(mimeType: String) {
        val image = ProductImage.of(byteArrayOf(1, 2, 3), mimeType)

        assertEquals(mimeType.substringBefore(';').trim().lowercase(), image.mimeType)
    }

    @Test
    fun `생성에 쓰인 원본 배열을 변경해도 ProductImage 내부는 영향받지 않는다`() {
        val original = byteArrayOf(1, 2, 3)
        val image = ProductImage.of(original, "image/png")

        original[0] = 99

        assertContentEquals(byteArrayOf(1, 2, 3), image.bytes)
    }

    @Test
    fun `bytes 로 반환된 배열을 변경해도 ProductImage 내부는 영향받지 않는다`() {
        val image = ProductImage.of(byteArrayOf(1, 2, 3), "image/png")

        image.bytes[0] = 99

        assertContentEquals(byteArrayOf(1, 2, 3), image.bytes)
    }

    @Test
    fun `빈 바이트 배열이면 예외가 발생한다`() {
        assertFailsWith<ProductImageException> {
            ProductImage.of(ByteArray(0), "image/png")
        }
    }

    @Test
    fun `MIME 타입이 null 이면 예외가 발생한다`() {
        assertFailsWith<ProductImageException> {
            ProductImage.of(byteArrayOf(1), null)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["image/gif", "image/bmp", "image/svg+xml", "application/pdf", "text/plain", ""])
    fun `지원하지 않는 MIME 타입이면 예외가 발생한다`(mimeType: String) {
        assertFailsWith<ProductImageException> {
            ProductImage.of(byteArrayOf(1), mimeType)
        }
    }

    @ParameterizedTest
    @CsvSource(
        "image/png, png",
        "image/jpeg, jpg",
        "image/webp, webp",
        "image/heic, heic",
        "image/heif, heif",
    )
    fun `MIME 타입에서 스토리지 object key 확장자를 도출한다`(
        mimeType: String,
        expected: String,
    ) {
        assertEquals(expected, ProductImage.of(byteArrayOf(1), mimeType).extension)
    }

    // 이미지 등록 v2 발급 단계는 바이트가 아직 없어 content-type 만으로 raw key 확장자를 만든다.
    @ParameterizedTest
    @CsvSource(
        "image/png, png",
        "image/jpeg, jpg",
        "image/webp, webp",
        "image/heic, heic",
        "image/heif, heif",
    )
    fun `content-type 만으로 바이트 없이 스토리지 확장자를 도출한다`(
        mimeType: String,
        expected: String,
    ) {
        assertEquals(expected, ProductImage.extensionForMimeType(mimeType))
    }

    @ParameterizedTest
    @CsvSource(
        "IMAGE/JPEG, jpg",
        "Image/Png, png",
        "image/webp; charset=utf-8, webp",
        "'  image/heic  ', heic",
    )
    fun `extensionForMimeType 도 of 와 같은 정규화를 거쳐 정확한 확장자를 도출한다`(
        mimeType: String,
        expected: String,
    ) {
        assertEquals(expected, ProductImage.extensionForMimeType(mimeType))
    }

    @ParameterizedTest
    @ValueSource(strings = ["image/gif", "image/svg+xml", "application/pdf", "text/plain", ""])
    fun `extensionForMimeType 은 지원하지 않는 타입이면 예외가 발생한다`(mimeType: String) {
        assertFailsWith<ProductImageException> {
            ProductImage.extensionForMimeType(mimeType)
        }
    }

    @Test
    fun `extensionForMimeType 은 content-type 이 null 이면 예외가 발생한다`() {
        assertFailsWith<ProductImageException> {
            ProductImage.extensionForMimeType(null)
        }
    }
}
