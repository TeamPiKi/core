package com.depromeet.piki.profile.controller

import com.depromeet.piki.common.openapi.OpenApiObjectMapper
import com.depromeet.piki.common.openapi.binds
import com.depromeet.piki.common.openapi.examples
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.profile.controller.dto.PublicFolderDetailResponse
import com.depromeet.piki.profile.controller.dto.PublicFolderResponse
import com.depromeet.piki.profile.controller.dto.PublicProfileResponse
import com.depromeet.piki.profile.domain.ProfileRelation
import com.depromeet.piki.user.domain.UserException
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import java.util.UUID

@Configuration
class PublicProfileApiExamples(
    private val openApiObjectMapper: OpenApiObjectMapper,
) {
    @Bean
    fun publicProfileOpenApiExamples(): OperationCustomizer =
        OperationCustomizer { operation, handlerMethod ->
            if (handlerMethod.binds(PublicProfileController::getProfile)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(status = HttpStatus.OK, name = "친구가 아닌 사람의 공개 프로필", payload = ApiResponseBody.ok(openProfile))
                    // 비공개와 "상대가 나를 차단" 이 같은 모양이다 — 가르면 차단 사실이 드러난다
                    add(
                        status = HttpStatus.OK,
                        name = "비공개 프로필 · 상대가 나를 차단 (구분되지 않음)",
                        payload = ApiResponseBody.ok(hiddenProfile),
                    )
                    add(status = HttpStatus.OK, name = "내가 차단한 사람", payload = ApiResponseBody.ok(blockedProfile))
                    add(UserException.notFound(), name = "없는 사용자 · 탈퇴한 사용자")
                }
            }
            if (handlerMethod.binds(PublicProfileController::getPublicFolder)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(status = HttpStatus.OK, name = "조회 성공 (빈 폴더)", payload = ApiResponseBody.ok(emptyFolder))
                    add(UserException.notFound(), name = "없는 폴더 · 비공개 폴더 · 프로필 비공개 · 차단")
                }
            }
            operation
        }

    companion object {
        private val ownerId = UUID.fromString("9c4f4e97-9c9c-4f4d-8a3d-4e5f60718293")

        private val openProfile =
            PublicProfileResponse(
                userId = ownerId,
                nickname = "난폭한 북극곰",
                profileImage = "https://cdn.piki.day/profiles/polar-bear.png",
                relation = ProfileRelation.NOT_FRIEND,
                folders = listOf(PublicFolderResponse(folderId = 12, name = "겨울 대비", wishCount = 7)),
            )

        private val hiddenProfile = openProfile.copy(folders = emptyList())

        private val blockedProfile = openProfile.copy(relation = ProfileRelation.BLOCKED, folders = emptyList())

        private val emptyFolder = PublicFolderDetailResponse(folderId = 12, name = "겨울 대비", wishes = emptyList())
    }
}
