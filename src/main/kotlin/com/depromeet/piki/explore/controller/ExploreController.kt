package com.depromeet.piki.explore.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.common.response.PageResponse
import com.depromeet.piki.explore.controller.dto.ExploreRecentResponse
import com.depromeet.piki.explore.controller.dto.ExploreWishDetailResponse
import com.depromeet.piki.explore.service.ExploreService
import com.depromeet.piki.explore.service.ExploreWishReader
import com.depromeet.piki.explore.service.dto.GetExploreRecentQuery
import com.depromeet.piki.explore.service.dto.GetExploreWishQuery
import com.depromeet.piki.wishlist.domain.WishCursor
import com.depromeet.piki.wishlist.domain.WishlistSize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/explore")
class ExploreController(
    private val exploreService: ExploreService,
    private val exploreWishReader: ExploreWishReader,
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
}
