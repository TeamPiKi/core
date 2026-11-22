package com.depromeet.piki.profile.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.profile.controller.dto.PublicFolderDetailResponse
import com.depromeet.piki.profile.controller.dto.PublicProfileResponse
import com.depromeet.piki.profile.service.PublicProfileService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/profiles")
class PublicProfileController(
    private val publicProfileService: PublicProfileService,
) : PublicProfileApi {
    @GetMapping("/{userId}")
    override fun getProfile(
        // permitAll — 토큰이 있으면 principal 이 채워지고, 없으면 null(비로그인)
        @AuthenticationPrincipal viewerId: UUID?,
        @PathVariable userId: UUID,
    ): ApiResponseBody<PublicProfileResponse> =
        ApiResponseBody.ok(PublicProfileResponse.from(publicProfileService.getProfile(viewerId, userId)))

    @GetMapping("/{userId}/folders/{folderId}")
    override fun getPublicFolder(
        @AuthenticationPrincipal viewerId: UUID?,
        @PathVariable userId: UUID,
        @PathVariable folderId: Long,
    ): ApiResponseBody<PublicFolderDetailResponse> {
        val detail = publicProfileService.getPublicFolder(viewerId, userId, folderId)
        return ApiResponseBody.ok(PublicFolderDetailResponse.from(detail))
    }
}
