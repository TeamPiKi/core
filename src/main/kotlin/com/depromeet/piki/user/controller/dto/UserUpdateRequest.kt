package com.depromeet.piki.user.controller.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

@Schema(description = "유저 정보 수정 요청 — 들어온 필드만 갱신한다")
data class UserUpdateRequest(
    // 게스트도 호출 가능한 PATCH 라 모든 필드 nullable — 하나만 / 둘 다 / 아무것도 안 바꿔도 된다.
    // 회원 전용 필드 추가 시에도 같은 PATCH 가 자기 권한 안의 필드만 수정하게 된다.
    @field:Size(min = 1, max = 10, message = NICKNAME_SIZE_MESSAGE)
    @field:Schema(description = "변경할 닉네임 (선택, 최대 10자)", example = "새닉네임", nullable = true)
    val nickname: String? = null,
    // 이미지 바이트는 이 요청에 실리지 않는다 — 클라가 presigned URL 로 S3 에 직접 올린 뒤 그 key 만 보낸다.
    // 형식·존재·실제 내용 검증은 서버가 이 key 로 raw 를 읽어 확정 단계에서 수행한다.
    @field:Schema(
        description = "업로드를 마친 프로필 이미지의 key (선택). POST /users/me/profile-image 로 발급받은 값을 그대로 보낸다",
        example = "items/raw/550e8400-e29b-41d4-a716-446655440000.png",
        nullable = true,
    )
    val imageKey: String? = null,
) {
    // Bean Validation 위반 메시지의 single source. OpenAPI example(UserApiExamples)이 같은 상수를 참조해
    // "필드 검증 문구가 @field 와 example 두 곳에서 따로 노는" 어긋남을 컴파일 타임에 막는다.
    companion object {
        const val NICKNAME_SIZE_MESSAGE = "닉네임은 1~10자 사이로 입력해 주세요."
    }
}
