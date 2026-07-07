package com.depromeet.piki.image.domain

/**
 * 추출 대상 이미지를 표현하는 값 객체.
 *
 * 빈 바이트 · 미지정 / 미지원 MIME 타입을 [of] 팩토리에서 차단한다.
 * 입력 형식 검증을 도메인 경계에 모아, 등록 경계(multipart 업로드 → S3 raw 적재)가
 * 항상 유효한 이미지만 다룬다는 것을 시그니처 수준에서 보장한다.
 */
class ProductImage private constructor(
    private val rawBytes: ByteArray,
    val mimeType: String,
) {
    // ByteArray 는 가변이므로 방어적 복사본을 노출한다.
    // 호출자가 받은 배열을 변경해도 ProductImage 내부 상태는 불변으로 유지된다.
    val bytes: ByteArray
        get() = rawBytes.copyOf()

    // 스토리지 object key 의 확장자. of() 가 SUPPORTED_MIME_TYPES 로 mimeType 을 보장하므로
    // else 는 도달 불가한 불변식 위반(코드 버그)이다.
    val extension: String
        get() = extensionOf(mimeType)

    companion object {
        // 이미지 추출이 받아들이는 형식 ↔ 스토리지 key 확장자 단일 매핑. 근거는 OCR 실행 주체인 PIKI-Extractor 의
        // Gemini Vision 지원 목록(https://ai.google.dev/gemini-api/docs/vision)이다 — 실행이 원격으로 이관돼 이 목록은
        // 그 능력의 사본이므로, extractor 의 지원 포맷이 바뀌면 여기도 함께 갱신해야 업로드 허용과 파싱 능력이 안 어긋난다.
        // SUPPORTED_MIME_TYPES(keys)·EXTENSIONS(values)·extensionOf 가 모두 이 map 을 파생해, 지원 포맷 추가가 한 곳으로 끝난다.
        private val MIME_TO_EXTENSION: Map<String, String> =
            mapOf(
                "image/png" to "png",
                "image/jpeg" to "jpg",
                "image/webp" to "webp",
                "image/heic" to "heic",
                "image/heif" to "heif",
            )

        val SUPPORTED_MIME_TYPES: Set<String> = MIME_TO_EXTENSION.keys

        // raw key 검증(RAW_KEY_REGEX 등)이 파생하는 지원 확장자 집합 — MIME_TO_EXTENSION 의 치역이라 포맷 추가 시 자동 추종한다.
        val EXTENSIONS: Set<String> = MIME_TO_EXTENSION.values.toSet()

        fun of(
            bytes: ByteArray,
            mimeType: String?,
        ): ProductImage {
            // 빈 파일·미지정/미지원 형식은 모두 사용자가 올린 이미지로 도달 가능한 계약 위반 → 400 (ProductImageException).
            if (bytes.isEmpty()) throw ProductImageException.emptyImage()
            return ProductImage(bytes.copyOf(), normalizeMimeType(mimeType))
        }

        // content-type 을 정규화·검증하고 스토리지 key 확장자를 돌려준다 — 바이트가 아직 없는 이미지 등록 v2 발급 단계에서,
        // 클라가 올릴 이미지의 content-type 만으로 raw key(items/raw/{UUID}.{ext})를 만드는 데 쓴다.
        // of() 와 같은 정규화·검증을 공유한다(미지정 400 unknownType · 미지원 400 unsupportedType).
        fun extensionForMimeType(mimeType: String?): String = extensionOf(normalizeMimeType(mimeType))

        // media type 정규화·검증 — of()·extensionForMimeType 가 공유한다.
        // RFC 상 media type 은 대소문자를 가리지 않고 `;` 뒤에 파라미터가 붙을 수 있어
        // (예: "IMAGE/JPEG", "image/jpeg; charset=utf-8") 정규화 후 비교한다.
        private fun normalizeMimeType(mimeType: String?): String {
            val type =
                (mimeType ?: throw ProductImageException.unknownType())
                    .substringBefore(';')
                    .trim()
                    .lowercase()
            if (type !in SUPPORTED_MIME_TYPES) throw ProductImageException.unsupportedType()
            return type
        }

        // 정규화된 mimeType 의 스토리지 key 확장자. SUPPORTED_MIME_TYPES(=MIME_TO_EXTENSION.keys)로 보장되므로 null 은 도달 불가한 코드 버그다.
        private fun extensionOf(mimeType: String): String =
            MIME_TO_EXTENSION[mimeType] ?: error("지원하지 않는 MIME 타입의 확장자를 요청했다: $mimeType")
    }
}
