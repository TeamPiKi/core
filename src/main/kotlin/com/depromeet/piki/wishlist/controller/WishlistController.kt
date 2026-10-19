package com.depromeet.piki.wishlist.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.common.response.PageResponse
import com.depromeet.piki.image.controller.dto.ConfirmImageUploadRequest
import com.depromeet.piki.image.controller.dto.PresignedImageUploadRequest
import com.depromeet.piki.image.controller.dto.PresignedImageUploadResponse
import com.depromeet.piki.metrics.registration.ExternalEntry
import com.depromeet.piki.metrics.registration.WishExternalEntryRecorder
import com.depromeet.piki.wishlist.controller.dto.WishDetailResponse
import com.depromeet.piki.wishlist.controller.dto.WishItemResponse
import com.depromeet.piki.wishlist.controller.dto.WishlistRegisterRequest
import com.depromeet.piki.wishlist.controller.dto.WishlistUpdateRequest
import com.depromeet.piki.wishlist.domain.WishCursor
import com.depromeet.piki.wishlist.domain.WishDeleteIds
import com.depromeet.piki.wishlist.domain.WishlistSize
import com.depromeet.piki.wishlist.service.WishlistService
import com.depromeet.piki.wishlist.service.dto.ConfirmWishImageRegistrationCommand
import com.depromeet.piki.wishlist.service.dto.DeleteWishCommand
import com.depromeet.piki.wishlist.service.dto.DeleteWishesCommand
import com.depromeet.piki.wishlist.service.dto.GetWishQuery
import com.depromeet.piki.wishlist.service.dto.GetWishlistQuery
import com.depromeet.piki.wishlist.service.dto.PresignWishImageUploadsCommand
import com.depromeet.piki.wishlist.service.dto.RefreshWishItemCommand
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/v1/wishlists")
class WishlistController(
    private val wishlistService: WishlistService,
    private val wishExternalEntryRecorder: WishExternalEntryRecorder,
) : WishlistApi {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun registerFromUrl(
        @AuthenticationPrincipal userId: UUID,
        @Valid @RequestBody request: WishlistRegisterRequest,
        @RequestHeader(name = ExternalEntry.HEADER, required = false) rawEntryPoint: String?,
    ): ApiResponseBody<WishItemResponse> {
        val result = wishlistService.registerFromUrl(request.toCommand(userId))
        wishExternalEntryRecorder.record(wishId = result.wish.getId(), rawEntryPoint = rawEntryPoint)
        return ApiResponseBody.created(WishItemResponse.from(result))
    }

    @PostMapping("/images/presigned")
    override fun presignImageUploads(
        @AuthenticationPrincipal userId: UUID,
        @RequestBody request: PresignedImageUploadRequest,
    ): ApiResponseBody<PresignedImageUploadResponse> {
        val uploads = wishlistService.presignImageUploads(PresignWishImageUploadsCommand(userId = userId, uploadFormats = request.toUploadFormats()))
        return ApiResponseBody.ok(PresignedImageUploadResponse.from(uploads))
    }

    @PostMapping("/images/confirm")
    @ResponseStatus(HttpStatus.CREATED)
    override fun confirmImageRegistration(
        @AuthenticationPrincipal userId: UUID,
        @RequestBody request: ConfirmImageUploadRequest,
    ): ApiResponseBody<List<WishItemResponse>> {
        val results = wishlistService.confirmImageRegistration(ConfirmWishImageRegistrationCommand(userId = userId, imageKeys = request.toImageKeys()))
        return ApiResponseBody.created(results.map(WishItemResponse::from))
    }

    @GetMapping
    override fun getWishlist(
        @AuthenticationPrincipal userId: UUID,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) size: Int?,
    ): ApiResponseBody<List<WishItemResponse>> {
        val page = wishlistService.getWishlist(GetWishlistQuery(userId = userId, cursor = WishCursor.parse(cursor), size = WishlistSize.of(size)))
        val data = page.entries.map(WishItemResponse::from)
        return ApiResponseBody.ok(
            data = data,
            pageResponse = PageResponse(nextCursor = page.nextCursor, hasNext = page.hasNext),
        )
    }

    @GetMapping("/{wishId}")
    override fun getWish(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable wishId: Long,
    ): ApiResponseBody<WishDetailResponse> {
        val result = wishlistService.getWish(GetWishQuery(userId = userId, wishId = wishId))
        return ApiResponseBody.ok(
            WishDetailResponse.from(result, requesterId = userId),
        )
    }

    @PatchMapping("/{wishId}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    override fun recoverWishItem(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable wishId: Long,
        @Valid @ModelAttribute request: WishlistUpdateRequest,
        @RequestPart("image", required = false) image: MultipartFile?,
    ): ApiResponseBody<WishItemResponse> {
        val result = wishlistService.recoverWishItem(request.toCommand(userId, wishId, image))
        return ApiResponseBody.ok(WishItemResponse.from(result))
    }

    @PostMapping("/{wishId}/refresh")
    override fun refreshWishItem(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable wishId: Long,
    ): ApiResponseBody<WishItemResponse> {
        val result = wishlistService.refreshWishItem(RefreshWishItemCommand(userId = userId, wishId = wishId))
        return ApiResponseBody.ok(WishItemResponse.from(result))
    }

    @DeleteMapping("/{wishId}")
    override fun deleteWish(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable wishId: Long,
    ): ApiResponseBody<Unit> {
        wishlistService.deleteWish(DeleteWishCommand(userId = userId, wishId = wishId))
        return ApiResponseBody.ok()
    }

    // DELETE body 는 게이트웨이·CDN 이 버릴 수 있어 query 로 받는다. required=true 면 누락이 캐치올 500 으로 샌다
    @DeleteMapping
    override fun deleteWishes(
        @AuthenticationPrincipal userId: UUID,
        @RequestParam(name = "ids", required = false) ids: List<Long>?,
    ): ApiResponseBody<Unit> {
        wishlistService.deleteWishes(DeleteWishesCommand(userId = userId, wishDeleteIds = WishDeleteIds.of(ids.orEmpty())))
        return ApiResponseBody.ok()
    }
}
