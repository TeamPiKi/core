package com.depromeet.piki.friendship.controller

import com.depromeet.piki.common.openapi.OpenApiObjectMapper
import com.depromeet.piki.common.openapi.binds
import com.depromeet.piki.common.openapi.examples
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.friendship.controller.dto.FriendListResponse
import com.depromeet.piki.friendship.controller.dto.FriendResponse
import com.depromeet.piki.friendship.domain.FriendshipException
import com.depromeet.piki.user.domain.UserException
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import java.util.UUID

@Configuration
class FriendshipApiExamples(
    private val openApiObjectMapper: OpenApiObjectMapper,
) {
    @Bean
    fun friendshipOpenApiExamples(): OperationCustomizer =
        OperationCustomizer { operation, handlerMethod ->
            if (handlerMethod.binds(FriendshipController::addFriend)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(status = HttpStatus.OK, name = "추가 성공 (이미 친구였어도 같음)", payload = ApiResponseBody.ok<Unit>())
                    add(FriendshipException.selfNotAllowed(), name = "자기 자신을 추가")
                    unauthorized()
                    add(FriendshipException.guestCannotUse(), name = "게스트의 친구 기능 이용 거부")
                    add(UserException.notFound(), name = "없는 사용자")
                    add(UserException.deletedUser(), name = "탈퇴한 계정 (요청자 또는 상대)")
                }
            }
            if (handlerMethod.binds(FriendshipController::removeFriend)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(status = HttpStatus.OK, name = "삭제 성공 (친구가 아니었어도 같음)", payload = ApiResponseBody.ok<Unit>())
                    unauthorized()
                    add(FriendshipException.guestCannotUse(), name = "게스트의 친구 기능 이용 거부")
                    add(UserException.deletedUser(), name = "탈퇴한 계정")
                }
            }
            if (handlerMethod.binds(FriendshipController::listFriends)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(status = HttpStatus.OK, name = "조회 성공", payload = ApiResponseBody.ok(sampleList))
                    add(status = HttpStatus.OK, name = "친구 없음", payload = ApiResponseBody.ok(emptyList))
                    unauthorized()
                    add(FriendshipException.guestCannotUse(), name = "게스트의 친구 기능 이용 거부")
                    add(UserException.deletedUser(), name = "탈퇴한 계정")
                }
            }
            operation
        }

    companion object {
        private val publicFriend =
            FriendResponse(
                userId = UUID.fromString("6f1c1b64-6f6f-4c1a-9f0a-1b2c3d4e5f60"),
                nickname = "난폭한 북극곰",
                profileImage = "https://cdn.piki.day/profiles/polar-bear.png",
                folderCount = 3,
                wishCount = 12,
            )

        // 프로필을 비공개로 둔 친구 — 두 수가 없어 응답에서 키가 빠진다. 0 과 구분된다
        private val privateFriend =
            FriendResponse(
                userId = UUID.fromString("7a2d2c75-7a7a-4d2b-8e1b-2c3d4e5f6071"),
                nickname = "졸린 참새",
                profileImage = "https://cdn.piki.day/profiles/sparrow.png",
                folderCount = null,
                wishCount = null,
            )

        private val sampleList = FriendListResponse(friendCount = 2, friends = listOf(publicFriend, privateFriend))

        private val emptyList = FriendListResponse(friendCount = 0, friends = emptyList())
    }
}
