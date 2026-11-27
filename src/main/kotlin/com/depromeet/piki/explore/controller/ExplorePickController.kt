package com.depromeet.piki.explore.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.common.response.PageResponse
import com.depromeet.piki.explore.controller.dto.ExplorePickCandidatesResponse
import com.depromeet.piki.explore.controller.dto.ExplorePickDetailResponse
import com.depromeet.piki.explore.controller.dto.ExplorePicksResponse
import com.depromeet.piki.explore.controller.dto.ExploreWishCopyResponse
import com.depromeet.piki.explore.service.ExploreCandidateCopier
import com.depromeet.piki.explore.service.ExplorePickReader
import com.depromeet.piki.explore.service.ExploreService
import com.depromeet.piki.explore.service.dto.CopyExploreCandidateCommand
import com.depromeet.piki.explore.service.dto.GetExplorePickQuery
import com.depromeet.piki.explore.service.dto.GetExplorePicksQuery
import com.depromeet.piki.wishlist.domain.WishCursor
import com.depromeet.piki.wishlist.domain.WishlistSize
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/explore/picks")
class ExplorePickController(
    private val exploreService: ExploreService,
    private val explorePickReader: ExplorePickReader,
    private val exploreCandidateCopier: ExploreCandidateCopier,
) : ExplorePickApi {
    @GetMapping
    override fun getPicks(
        @AuthenticationPrincipal viewerId: UUID?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) size: Int?,
    ): ApiResponseBody<ExplorePicksResponse> {
        val result =
            exploreService.getPicks(
                GetExplorePicksQuery(viewerId = viewerId, cursor = WishCursor.parse(cursor), size = WishlistSize.of(size)),
            )
        return ApiResponseBody.ok(
            data = ExplorePicksResponse.from(result),
            pageResponse = PageResponse(nextCursor = result.nextCursor, hasNext = result.hasNext),
        )
    }

    @GetMapping("/{pickId}")
    override fun getPick(
        @AuthenticationPrincipal viewerId: UUID?,
        @PathVariable pickId: Long,
    ): ApiResponseBody<ExplorePickDetailResponse> {
        val result = explorePickReader.getPick(GetExplorePickQuery(viewerId = viewerId, pickId = pickId))
        return ApiResponseBody.ok(ExplorePickDetailResponse.from(result))
    }

    @GetMapping("/{pickId}/candidates")
    override fun getCandidates(
        @AuthenticationPrincipal viewerId: UUID?,
        @PathVariable pickId: Long,
    ): ApiResponseBody<ExplorePickCandidatesResponse> {
        val result = explorePickReader.getCandidates(GetExplorePickQuery(viewerId = viewerId, pickId = pickId))
        return ApiResponseBody.ok(ExplorePickCandidatesResponse.from(result))
    }

    // 위시 담기와 같은 이유로 201 이 아니라 200 이다
    @PostMapping("/{pickId}/candidates/{candidateId}/copy")
    @ResponseStatus(HttpStatus.OK)
    override fun copyCandidate(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable pickId: Long,
        @PathVariable candidateId: Long,
    ): ApiResponseBody<ExploreWishCopyResponse> {
        val result =
            exploreCandidateCopier.copy(CopyExploreCandidateCommand(userId = userId, pickId = pickId, candidateId = candidateId))
        return ApiResponseBody.ok(ExploreWishCopyResponse.from(result))
    }
}
