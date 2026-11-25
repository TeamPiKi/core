package com.depromeet.piki.explore.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.common.response.PageResponse
import com.depromeet.piki.explore.controller.dto.ExploreRecentResponse
import com.depromeet.piki.explore.controller.dto.ExploreFriendsResponse
import com.depromeet.piki.explore.controller.dto.ExploreWishCopyResponse
import com.depromeet.piki.explore.controller.dto.ExploreWishDetailResponse
import com.depromeet.piki.explore.service.ExploreFriendsReader
import com.depromeet.piki.explore.service.ExploreService
import com.depromeet.piki.explore.service.ExploreWishCopier
import com.depromeet.piki.explore.service.ExploreWishReader
import com.depromeet.piki.explore.service.dto.CopyExploreWishCommand
import com.depromeet.piki.explore.service.dto.GetExploreFriendsQuery
import com.depromeet.piki.explore.service.dto.GetExploreRecentQuery
import com.depromeet.piki.explore.service.dto.GetExploreWishQuery
import com.depromeet.piki.wishlist.domain.WishCursor
import com.depromeet.piki.wishlist.domain.WishlistSize
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/explore")
class ExploreController(
    private val exploreService: ExploreService,
    private val exploreWishReader: ExploreWishReader,
    private val exploreWishCopier: ExploreWishCopier,
    private val exploreFriendsReader: ExploreFriendsReader,
) : ExploreApi {
    @GetMapping("/recent")
    override fun getRecent(
        @AuthenticationPrincipal viewerId: UUID?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) size: Int?,
    ): ApiResponseBody<ExploreRecentResponse> {
        val result =
            exploreService.getRecent(
                GetExploreRecentQuery(viewerId = viewerId, cursor = WishCursor.parse(cursor), size = WishlistSize.of(size)),
            )
        return ApiResponseBody.ok(
            data = ExploreRecentResponse.from(result),
            pageResponse = PageResponse(nextCursor = result.nextCursor, hasNext = result.hasNext),
        )
    }

    @GetMapping("/wishes/{wishId}")
    override fun getWish(
        @AuthenticationPrincipal viewerId: UUID?,
        @PathVariable wishId: Long,
    ): ApiResponseBody<ExploreWishDetailResponse> {
        val result = exploreWishReader.getWish(GetExploreWishQuery(viewerId = viewerId, wishId = wishId))
        return ApiResponseBody.ok(ExploreWishDetailResponse.from(result))
    }

    // 이미 가진 상품이면 새로 만들지 않는 멱등 동작이라 201 이 아니라 200 이다
    @PostMapping("/wishes/{wishId}/copy")
    @ResponseStatus(HttpStatus.OK)
    override fun copyWish(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable wishId: Long,
    ): ApiResponseBody<ExploreWishCopyResponse> {
        val result = exploreWishCopier.copy(CopyExploreWishCommand(userId = userId, wishId = wishId))
        return ApiResponseBody.ok(ExploreWishCopyResponse.from(result))
    }

    @GetMapping("/friends")
    override fun getFriendWishes(
        @AuthenticationPrincipal userId: UUID,
        @RequestParam(required = false) friendId: UUID?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) size: Int?,
    ): ApiResponseBody<ExploreFriendsResponse> {
        val result =
            exploreFriendsReader.getFriendWishes(
                GetExploreFriendsQuery(
                    viewerId = userId,
                    friendId = friendId,
                    cursor = WishCursor.parse(cursor),
                    size = WishlistSize.of(size),
                ),
            )
        return ApiResponseBody.ok(
            data = ExploreFriendsResponse.from(result),
            pageResponse = PageResponse(nextCursor = result.nextCursor, hasNext = result.hasNext),
        )
    }
}
