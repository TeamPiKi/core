package com.depromeet.piki.common.imageproxy

import com.depromeet.piki.common.response.ApiResponseBody
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

@Tag(name = "Image Proxy", description = "외부 이미지 프록시 API")
interface ImageProxyApi {
    @Operation(
        summary = "외부 이미지 프록시",
        description = """
            CORS 제약으로 프론트엔드가 직접 불러올 수 없는 외부 CDN 이미지를 서버가 중계합니다.
            허용 도메인(무신사·네이버·카카오 등)의 URL 만 처리하며, 5 MB 초과 이미지는 거부됩니다.
            성공 시 이미지 바이너리를 원본 Content-Type 과 함께 반환합니다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "이미지 바이너리 반환",
                content = [Content(mediaType = "image/*")],
            ),
            ApiResponse(
                responseCode = "400",
                description =
                    "잘못된 요청 (허용되지 않은 도메인 · https 외 스킴 — code: PROXY-001 · " +
                        "이미지 크기 5 MB 초과 — code: PROXY-002)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "401",
                description = "미인증 (JWT 토큰 없음 또는 유효하지 않음)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "502",
                description = "외부 이미지 서버 오류 (이미지를 불러올 수 없음 · 재시도 가능) — code: PROXY-003",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun proxyImage(
        @Parameter(description = "프록시할 이미지 URL", required = true)
        url: String,
    ): ResponseEntity<ByteArray>
}
