package com.depromeet.piki.user.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.image.controller.dto.PresignedImageUpload
import com.depromeet.piki.user.controller.dto.MyProfileResponse
import com.depromeet.piki.user.controller.dto.NicknameCheckRequest
import com.depromeet.piki.user.controller.dto.NicknameCheckResponse
import com.depromeet.piki.user.controller.dto.ProfileImagePresignRequest
import com.depromeet.piki.user.controller.dto.UserResponse
import com.depromeet.piki.user.controller.dto.UserUpdateRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID
import org.springframework.http.MediaType

@Tag(name = "User", description = "유저 API")
interface UserApi {
    @Operation(
        summary = "내 정보 조회",
        description =
            "현재 로그인된 유저(**GUEST 포함**)의 정보를 조회한다. 소셜 계정 `email` 도 함께 내려준다 " +
                "(미수집·미동의·backfill 전이면 `null`).",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공 (email 은 미수집·미동의 시 null)",
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
                responseCode = "404",
                description = "유저를 찾을 수 없음 (JWT 유효하지만 DB에서 유저가 삭제된 경우) — code: USER-001",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "탈퇴한 계정 (JWT 는 아직 유효하나 계정이 탈퇴 상태) — code: USER-003",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun getMe(
        @Parameter(hidden = true) userId: UUID,
    ): ApiResponseBody<MyProfileResponse>

    @Operation(
        summary = "프로필 이미지 업로드 URL 발급",
        description =
            "프로필 이미지를 올릴 **presigned URL** 을 발급한다. 바이트는 서버를 거치지 않고 클라가 S3 로 직접 PUT 한다.\n\n" +
                "**흐름**\n\n" +
                "1. 이 API 로 `contentType` 을 보내 `uploadUrl` · `imageKey` 를 받는다.\n" +
                "2. `uploadUrl` 에 이미지를 PUT 한다 (`Content-Type` 헤더는 응답의 `contentType` 과 같아야 한다 — 서명에 박혀 있어 다르면 S3 가 거부).\n" +
                "3. `PATCH /users/me` 에 `imageKey` 를 실어 확정한다. 서버가 그 원본을 읽어 검증하고 최종 경로에 저장한다.\n\n" +
                "- **MEMBER 전용** — 게스트는 발급 단계에서 403 으로 막는다 (올릴 기회 자체를 주지 않는다).\n" +
                "- 허용 형식: `image/png` · `image/jpeg` · `image/webp` · `image/heic` · `image/heif`.\n" +
                "- 발급된 URL 은 5분 뒤 만료된다. 확정되지 않은 원본은 하루 뒤 자동 삭제된다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "발급 성공 (uploadUrl · imageKey · contentType)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description =
                    "잘못된 요청\n\n" +
                        "- `contentType` 이 비어 있음 (형식 검증 400, COMMON-INVALID-INPUT)\n" +
                        "- 지원하지 않는 형식 (`png`/`jpeg`/`webp`/`heic`/`heif` 만 허용) (USER-010)",
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
                responseCode = "403",
                description = "권한 없음 (GUEST 는 프로필 이미지를 올릴 수 없다 — MEMBER 전용) — code: USER-008",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "유저를 찾을 수 없음 (JWT 유효하지만 DB에서 유저가 삭제된 경우) — code: USER-001",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "탈퇴한 유저 — code: USER-003",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "502",
                description = "외부 의존성 실패 (업로드 URL 발급 실패 — 재시도 가능) — code: STORAGE-002",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun presignProfileImage(
        @Parameter(hidden = true) userId: UUID,
        request: ProfileImagePresignRequest,
    ): ApiResponseBody<PresignedImageUpload>

    @Operation(
        summary = "내 정보 수정",
        description =
            "내 정보(`nickname` · 프로필 이미지)를 한 요청(JSON)으로 **부분 수정**한다. " +
                "들어온 필드만 갱신하며, 둘 다 보내면 한 트랜잭션에 묶여 함께 반영되고, 아무 필드도 안 보내면 변화 없이 200 으로 통과한다.\n\n" +
                "**이미지는 이 요청에 바이트로 싣지 않는다.** `POST /users/me/profile-image` 로 발급받은 URL 에 클라가 " +
                "S3 로 직접 올린 뒤, 그 `imageKey` 만 여기에 보낸다. 서버는 그 key 로 원본을 읽어 형식을 검증하고 최종 경로에 저장한다.\n\n" +
                "**필드별 권한·동작**\n\n" +
                "| 필드 | 권한 | 동작 |\n" +
                "|---|---|---|\n" +
                "| `nickname` | GUEST·MEMBER | 닉네임 변경 |\n" +
                "| `imageKey` | **MEMBER 전용** | 업로드된 원본 검증 → S3 저장 → 그 URL 로 `profileImage` 갱신 |\n\n" +
                "- GUEST 가 `imageKey` 를 담아 호출하면 **403** 으로 거부한다 (닉네임 동반 여부와 무관하게 요청 전체 거부).\n" +
                "- 이미지 허용 형식: `png` / `jpeg` / `webp` / `heic` / `heif` (그 외는 발급 단계에서 400).",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "수정 성공 (갱신된 nickname · profileImage 포함. 빈 요청이면 기존 값 그대로)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description =
                    "잘못된 요청\n\n" +
                        "- **닉네임** — 빈 문자열 · 10자 초과 (형식 검증 400, COMMON-INVALID-INPUT) · " +
                        "공백만 입력 (USER-006 — `@Size(min = 1)` 을 통과해 도메인이 잡는다) · " +
                        "'탈퇴' 예약 prefix 로 시작 (USER-013)\n" +
                        "- **이미지 key** — 우리가 발급하지 않은 형식의 key (UPLOAD-001) · 아직 S3 에 올라오지 않은 key (UPLOAD-002)\n" +
                        "- **이미지 내용** — 빈 파일 (USER-009) · 발급 때 선언한 형식과 실제 파일 내용 불일치(위조·손상) (USER-011)",
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
                responseCode = "403",
                description = "권한 없음 (GUEST 가 프로필 이미지 수정을 시도 — 이미지 수정은 MEMBER 전용) — code: USER-008",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "유저를 찾을 수 없음 (JWT 유효하지만 DB에서 유저가 삭제된 경우) — code: USER-001",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "409",
                description = "상태 충돌 (닉네임 중복 USER-004 · 탈퇴한 유저 USER-003)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "502",
                description =
                    "외부 의존성 실패 (이미지 저장소(S3) — 재시도 가능)\n\n" +
                        "- 업로드 상태 확인 실패 (STORAGE-003) · 원본 읽기 실패 (STORAGE-005) · 저장 실패 (STORAGE-001)",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun updateMe(
        @Parameter(hidden = true) userId: UUID,
        request: UserUpdateRequest,
    ): ApiResponseBody<UserResponse>

    @Operation(
        summary = "회원 탈퇴",
        description =
            "현재 로그인된 **MEMBER** 의 계정을 탈퇴 처리한다. 게스트는 탈퇴 대상이 아니라 403 으로 거부하며, **멱등**이라 재요청해도 200.\n\n" +
                "**데이터 처리**\n\n" +
                "- `users` 행은 익명 tombstone 으로 남겨 공유 토너먼트 참조를 보존한다.\n" +
                "- 소셜 식별자(`user_details`)·기기 토큰(`user_devices`)·위시·알림은 즉시 **하드삭제** (PIPA 지체없이 파기).\n" +
                "- refresh token 무효화·SSE 연결 종료까지 함께 처리한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "탈퇴 성공 (data=null)",
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
                responseCode = "403",
                description = "게스트는 탈퇴할 수 없음 (탈퇴는 MEMBER 전용) — code: USER-007",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "유저를 찾을 수 없음 (JWT 유효하지만 DB에서 유저가 삭제된 경우) — code: USER-001",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
        ],
    )
    fun withdraw(
        @Parameter(hidden = true) userId: UUID,
    ): ApiResponseBody<Unit>

    @Operation(
        summary = "닉네임 중복 체크",
        description =
            "닉네임이 이미 다른 유저에게 점유됐는지 확인한다. 회원 전환 / 닉네임 수정 전 사전 확인용.\n\n" +
                "- 본인의 현재 닉네임은 중복으로 잡지 않는다 — 자기 닉네임 유지 / 자기 닉네임으로 재확인 흐름이 자연스럽게 통과한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "확인 성공",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = ApiResponseBody::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "닉네임 형식 검증 실패 (공백 · 10자 초과 — Bean Validation 400, code 없음)",
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
        ],
    )
    fun checkNickname(
        @Parameter(hidden = true) userId: UUID?,
        request: NicknameCheckRequest,
    ): ApiResponseBody<NicknameCheckResponse>
}
