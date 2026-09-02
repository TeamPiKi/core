package com.depromeet.piki.user.domain

/**
 * 업로드된 프로필 이미지 파일을 표현하는 값 객체.
 *
 * 빈 바이트 · 미지정 / 미지원 MIME 타입 · 선언과 실제 내용 불일치를 [of] 팩토리에서 [UserException] 으로 차단한다.
 * 형식 검증을 도메인 경계에 모아, 저장 어댑터(ImageStorage)는 항상 유효한 이미지만 받는다는 것을
 * 시그니처 수준에서 보장한다. 프로필 이미지는 User 의 속성이므로 nickname 검증과 같이 UserException 을 쓴다.
 */
class ProfileImageFile private constructor(
    private val rawBytes: ByteArray,
    val mimeType: String,
) {
    // ByteArray 는 가변이므로 방어적 복사본을 노출한다.
    val bytes: ByteArray
        get() = rawBytes.copyOf()

    // 스토리지 object key 의 확장자. of() 가 SUPPORTED_MIME_TYPES 로 mimeType 을 보장하므로
    // null 은 도달 불가한 불변식 위반(코드 버그)이다.
    val extension: String
        get() = MIME_TO_EXTENSION[mimeType] ?: error("지원하지 않는 MIME 타입의 확장자를 요청했다: $mimeType")

    companion object {
        // 프로필 이미지로 허용하는 정적 사진 형식과 그 스토리지 확장자. gif(애니메이션)·svg(XSS 벡터)는 제외한다.
        // ProductImage 의 Gemini Vision 지원 목록과 우연히 겹치지만, 프로필 허용 정책은 그와 독립이며
        // 한쪽이 바뀌어도 다른 쪽을 끌고 가지 않도록 별도 값 객체로 둔다.
        // SUPPORTED_MIME_TYPES(keys)·extension·extensionForMimeType·mimeTypeForExtension 이 모두 이 map 을
        // 파생해, 지원 포맷 추가가 한 곳으로 끝나고 방향별로 어긋나지 않는다.
        private val MIME_TO_EXTENSION: Map<String, String> =
            mapOf(
                "image/png" to "png",
                "image/jpeg" to "jpg",
                "image/webp" to "webp",
                "image/heic" to "heic",
                "image/heif" to "heif",
            )

        val SUPPORTED_MIME_TYPES: Set<String> = MIME_TO_EXTENSION.keys

        // 매직바이트 시그니처 — 각 파일 포맷 명세(PNG/JPEG 스펙·WebP RIFF·ISO BMFF)가 박아 둔 불변 상수다.
        // 배포·환경·설정과 무관하게 영원히 고정이라 외부화(properties/env)할 값이 아니라 코드 상수로 둔다.
        // 인라인 매직넘버 대신 named const 로 의미를 박아 "이 숫자가 무슨 포맷의 시그니처인지" 가 드러나게 한다.
        private val PNG_SIGNATURE = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val JPEG_SIGNATURE = intArrayOf(0xFF, 0xD8, 0xFF)
        private const val RIFF = "RIFF" // WebP 컨테이너 시작 (offset 0)
        private const val WEBP = "WEBP" // WebP form type (offset 8)
        private const val FTYP = "ftyp" // ISO BMFF(HEIC/HEIF) 박스 타입 (offset 4)

        // HEIC/HEIF 는 단순 prefix 가 아니라 ISO BMFF 컨테이너라 offset 4 의 "ftyp" 박스 + brand 로 식별한다.
        // brand 는 iOS 버전·인코더마다 다양하므로 정상 파일을 거부하지 않게 흔한 이미지 brand 를 넓게 허용한다.
        private val HEIF_BRANDS: Set<String> =
            setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs", "mif1", "msf1", "miaf")

        // presigned 발급 단계용 — 바이트가 아직 없을 때 content-type 만 검증해 key 확장자를 정한다.
        // of() 와 같은 정규화·화이트리스트를 거치므로, 발급을 통과한 형식은 확정 단계의 of() 도 통과한다
        // (바이트 시그니처 불일치만 그때 걸러진다 — 발급 시점엔 확인할 바이트가 없다).
        fun extensionForMimeType(contentType: String?): String =
            MIME_TO_EXTENSION[normalize(contentType)] ?: throw UserException.unsupportedProfileImageType()

        // 확정 단계용 — 우리가 발급한 key 의 확장자로부터 "클라가 선언했던" MIME 을 되찾는다.
        // 확장자는 발급 때 우리가 붙인 값이라 신뢰할 수 있고, 이 값과 실제 바이트의 일치를 of() 가 검증한다.
        // 우리 key 에서만 오므로 미지원 확장자는 도달 불가한 불변식 위반이다.
        fun mimeTypeForExtension(extension: String): String =
            MIME_TO_EXTENSION.entries.firstOrNull { it.value == extension }?.key
                ?: error("우리가 발급하지 않은 확장자다: $extension")

        // media type 은 RFC 상 대소문자를 가리지 않고 `;` 뒤에 파라미터가 붙을 수 있다.
        // (예: "IMAGE/JPEG", "image/jpeg; charset=utf-8") 비교 전에 정규화한다.
        private fun normalize(contentType: String?): String =
            contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
                ?: throw UserException.unsupportedProfileImageType()

        fun of(
            bytes: ByteArray,
            contentType: String?,
        ): ProfileImageFile {
            if (bytes.isEmpty()) throw UserException.emptyProfileImage()
            val type = normalize(contentType)
            if (type !in SUPPORTED_MIME_TYPES) throw UserException.unsupportedProfileImageType()
            // Content-Type 헤더는 클라이언트가 위조 가능하므로 실제 바이트 시그니처와 교차검증한다.
            // (아이템 파싱 경로는 Gemini Vision 이 다운스트림에서 실질 검증을 대신하지만, 프로필은 그 공백이 있다.)
            if (!matchesSignature(type, bytes)) throw UserException.malformedProfileImage()
            return ProfileImageFile(bytes.copyOf(), type)
        }

        // 선언한 MIME 과 실제 파일의 매직바이트가 일치하는지 검사한다.
        private fun matchesSignature(
            mimeType: String,
            bytes: ByteArray,
        ): Boolean =
            when (mimeType) {
                "image/png" -> bytes.startsWith(PNG_SIGNATURE)
                "image/jpeg" -> bytes.startsWith(JPEG_SIGNATURE)
                "image/webp" -> bytes.asciiAt(0, RIFF) && bytes.asciiAt(8, WEBP)
                "image/heic", "image/heif" -> bytes.asciiAt(4, FTYP) && bytes.heifBrand() in HEIF_BRANDS
                else -> false // of() 가 화이트리스트를 보장하므로 도달 불가
            }

        private fun ByteArray.startsWith(signature: IntArray): Boolean {
            if (size < signature.size) return false
            return signature.indices.all { this[it] == signature[it].toByte() }
        }

        private fun ByteArray.asciiAt(
            offset: Int,
            ascii: String,
        ): Boolean {
            if (size < offset + ascii.length) return false
            return ascii.indices.all { this[offset + it] == ascii[it].code.toByte() }
        }

        // ISO BMFF 의 major brand (offset 8~11, 4바이트 ASCII).
        private fun ByteArray.heifBrand(): String {
            if (size < 12) return ""
            return buildString { (8..11).forEach { append(this@heifBrand[it].toInt().toChar()) } }.lowercase()
        }
    }
}
