package com.depromeet.piki.block.controller

import com.depromeet.piki.block.controller.dto.BlockListResponse
import com.depromeet.piki.block.controller.dto.BlockedUserResponse
import com.depromeet.piki.block.domain.UserBlockException
import com.depromeet.piki.common.openapi.OpenApiObjectMapper
import com.depromeet.piki.common.openapi.binds
import com.depromeet.piki.common.openapi.examples
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.friendship.controller.FriendshipController
import com.depromeet.piki.user.domain.UserException
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import java.util.UUID

@Configuration
class UserBlockApiExamples(
    private val openApiObjectMapper: OpenApiObjectMapper,
) {
    @Bean
    fun userBlockOpenApiExamples(): OperationCustomizer =
        OperationCustomizer { operation, handlerMethod ->
            // 친구 추가가 차단 관계에서 내는 404. 친구 쪽 문서가 차단 예외를 참조하지 않게 차단 쪽에서 예시를 더한다
            if (handlerMethod.binds(FriendshipController::addFriend)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(UserException.notFound(), name = "어느 한쪽이 차단한 사이 (차단 사실을 드러내지 않는 같은 404)")
                }
            }
            if (handlerMethod.binds(UserBlockController::block)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(status = HttpStatus.OK, name = "차단 성공 (이미 차단했어도 같음)", payload = ApiResponseBody.ok<Unit>())
                    add(UserBlockException.selfNotAllowed(), name = "자기 자신을 차단")
                    unauthorized()
                    add(UserBlockException.guestCannotUse(), name = "게스트의 차단 기능 이용 거부")
                    add(UserException.notFound(), name = "없는 사용자 · 탈퇴한 상대")
                    add(UserException.deletedUser(), name = "요청자 본인이 탈퇴한 계정")
                }
            }
            if (handlerMethod.binds(UserBlockController::unblock)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(status = HttpStatus.OK, name = "해제 성공 (차단한 적 없어도 같음)", payload = ApiResponseBody.ok<Unit>())
                    unauthorized()
                    add(UserBlockException.guestCannotUse(), name = "게스트의 차단 기능 이용 거부")
                    add(UserException.deletedUser(), name = "요청자 본인이 탈퇴한 계정")
                }
            }
            if (handlerMethod.binds(UserBlockController::listBlocked)) {
                operation.examples(openApiObjectMapper.delegate) {
                    add(status = HttpStatus.OK, name = "조회 성공", payload = ApiResponseBody.ok(sampleList))
                    add(status = HttpStatus.OK, name = "차단한 사람 없음", payload = ApiResponseBody.ok(emptyList))
                    unauthorized()
                    add(UserBlockException.guestCannotUse(), name = "게스트의 차단 기능 이용 거부")
                    add(UserException.deletedUser(), name = "요청자 본인이 탈퇴한 계정")
                }
            }
            operation
        }

    companion object {
        private val sampleList =
            BlockListResponse(
                blocked =
                    listOf(
                        BlockedUserResponse(
                            userId = UUID.fromString("8b3e3d86-8b8b-4e3c-9f2c-3d4e5f607182"),
                            nickname = "난폭한 북극곰",
                            profileImage = "https://cdn.piki.day/profiles/polar-bear.png",
                        ),
                    ),
            )

        private val emptyList = BlockListResponse(blocked = emptyList())
    }
}
