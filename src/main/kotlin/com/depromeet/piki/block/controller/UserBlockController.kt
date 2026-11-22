package com.depromeet.piki.block.controller

import com.depromeet.piki.block.controller.dto.BlockListResponse
import com.depromeet.piki.block.service.UserBlockService
import com.depromeet.piki.common.response.ApiResponseBody
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
@RequestMapping("/api/v1/blocks")
class UserBlockController(
    private val userBlockService: UserBlockService,
) : UserBlockApi {
    // 차단·해제가 모두 멱등이라 201 이 아니라 200 이다
    @PostMapping("/{blockedId}")
    @ResponseStatus(HttpStatus.OK)
    override fun block(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable blockedId: UUID,
    ): ApiResponseBody<Unit> {
        userBlockService.block(userId, blockedId)
        return ApiResponseBody.ok()
    }

    @DeleteMapping("/{blockedId}")
    @ResponseStatus(HttpStatus.OK)
    override fun unblock(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable blockedId: UUID,
    ): ApiResponseBody<Unit> {
        userBlockService.unblock(userId, blockedId)
        return ApiResponseBody.ok()
    }

    @GetMapping
    override fun listBlocked(
        @AuthenticationPrincipal userId: UUID,
    ): ApiResponseBody<BlockListResponse> = ApiResponseBody.ok(BlockListResponse.from(userBlockService.list(userId)))
}
