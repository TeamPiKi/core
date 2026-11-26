package com.depromeet.piki.friendship.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.friendship.controller.dto.FriendListResponse
import com.depromeet.piki.friendship.service.FriendshipService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/friends")
class FriendshipController(
    private val friendshipService: FriendshipService,
) : FriendshipApi {
    // 추가·삭제가 모두 멱등이라 201 이 아니라 200 이다 — "이미 친구" 와 "방금 만듦" 을 응답으로 가르지 않는다
    @PostMapping("/{friendId}")
    @ResponseStatus(HttpStatus.OK)
    override fun addFriend(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable friendId: UUID,
    ): ApiResponseBody<Unit> {
        friendshipService.add(userId, friendId)
        return ApiResponseBody.ok()
    }

    @DeleteMapping("/{friendId}")
    @ResponseStatus(HttpStatus.OK)
    override fun removeFriend(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable friendId: UUID,
    ): ApiResponseBody<Unit> {
        friendshipService.remove(userId, friendId)
        return ApiResponseBody.ok()
    }

    @GetMapping
    override fun listFriends(
        @AuthenticationPrincipal userId: UUID,
    ): ApiResponseBody<FriendListResponse> = ApiResponseBody.ok(FriendListResponse.from(friendshipService.list(userId)))
}
