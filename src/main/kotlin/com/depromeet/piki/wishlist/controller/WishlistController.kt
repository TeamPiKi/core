package com.depromeet.piki.wishlist.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.common.response.PageResponse
import com.depromeet.piki.image.controller.dto.ConfirmImageUploadRequest
import com.depromeet.piki.image.controller.dto.PresignedImageUploadRequest
import com.depromeet.piki.image.controller.dto.PresignedImageUploadResponse
import com.depromeet.piki.wishlist.controller.dto.WishItemResponse
import com.depromeet.piki.wishlist.controller.dto.WishPriceHistoryResponse
import com.depromeet.piki.wishlist.controller.dto.WishlistRegisterRequest
import com.depromeet.piki.wishlist.controller.dto.WishlistUpdateRequest
import com.depromeet.piki.wishlist.domain.WishDeleteIds
import com.depromeet.piki.wishlist.service.WishlistService
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
) : WishlistApi {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun registerFromUrl(
        @AuthenticationPrincipal userId: UUID,
        @Valid @RequestBody request: WishlistRegisterRequest,
    ): ApiResponseBody<WishItemResponse> {
        val result = wishlistService.registerFromUrl(rawUrl = request.url, userId = userId)
        return ApiResponseBody.created(WishItemResponse.from(result.wish, result.item, result.snapshot))
    }

    @PostMapping("/images", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    override fun registerFromImages(
        @AuthenticationPrincipal userId: UUID,
        @RequestParam("images", required = false) images: List<MultipartFile>?,
    ): ApiResponseBody<List<WishItemResponse>> {
        // images 파트를 아예 안 보내면(0장) Spring 이 컨트롤러 진입 전 MissingServletRequestPartException 으로
        // 끊어 캐치올(500)로 떨어진다. required=false + orEmpty 로 항상 서비스 검증(invalidImageCount, 400)에 닿게 한다.
        val results = wishlistService.registerFromImages(images = images.orEmpty(), userId = userId)
        return ApiResponseBody.created(results.map { WishItemResponse.from(it.wish, it.item, it.snapshot) })
    }

    // 이미지 등록 v2 1단계 — presigned 업로드 URL 발급. pending_uploads 에 발급 기록만 남기고 Wish·Item 은 아직 만들지 않으므로 200 OK.
    @PostMapping("/images/presigned")
    override fun presignImageUploads(
        @AuthenticationPrincipal userId: UUID,
        @RequestBody request: PresignedImageUploadRequest,
    ): ApiResponseBody<PresignedImageUploadResponse> {
        val uploads = wishlistService.presignImageUploads(contentTypes = request.contentTypes, userId = userId)
        return ApiResponseBody.ok(PresignedImageUploadResponse.from(uploads))
    }

    // 이미지 등록 v2 2단계 — 업로드 확정. PENDING 위시를 생성하므로 v1(registerFromImages)과 같은 201 CREATED.
    @PostMapping("/images/confirm")
    @ResponseStatus(HttpStatus.CREATED)
    override fun confirmImageRegistration(
        @AuthenticationPrincipal userId: UUID,
        @RequestBody request: ConfirmImageUploadRequest,
    ): ApiResponseBody<List<WishItemResponse>> {
        val results = wishlistService.confirmImageRegistration(imageKeys = request.imageKeys, userId = userId)
        return ApiResponseBody.created(results.map { WishItemResponse.from(it.wish, it.item, it.snapshot) })
    }

    @GetMapping
    override fun getWishlist(
        @AuthenticationPrincipal userId: UUID,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) size: Int?,
    ): ApiResponseBody<List<WishItemResponse>> {
        val page = wishlistService.getWishlist(userId = userId, rawCursor = cursor, rawSize = size)
        val data = page.entries.map { WishItemResponse.from(it.wish, it.item, it.snapshot) }
        return ApiResponseBody.ok(
            data = data,
            pageResponse = PageResponse(nextCursor = page.nextCursor, hasNext = page.hasNext),
        )
    }

    @GetMapping("/{wishId}")
    override fun getWish(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable wishId: Long,
    ): ApiResponseBody<WishItemResponse> {
        val result = wishlistService.getWish(userId = userId, wishId = wishId)
        return ApiResponseBody.ok(WishItemResponse.from(result.wish, result.item, result.snapshot))
    }

    @GetMapping("/{wishId}/history")
    override fun getPriceHistory(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable wishId: Long,
    ): ApiResponseBody<WishPriceHistoryResponse> {
        val result = wishlistService.getPriceHistory(userId = userId, wishId = wishId)
        return ApiResponseBody.ok(WishPriceHistoryResponse.from(result.wish, result.item, result.history))
    }

    @PatchMapping("/{wishId}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    override fun recoverWishItem(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable wishId: Long,
        @Valid @ModelAttribute request: WishlistUpdateRequest,
        @RequestPart("image", required = false) image: MultipartFile?,
    ): ApiResponseBody<WishItemResponse> {
        val result =
            wishlistService.recoverWishItem(
                userId = userId,
                wishId = wishId,
                name = request.name,
                currentPrice = request.currentPrice,
                currency = request.currency,
                image = image,
            )
        return ApiResponseBody.ok(WishItemResponse.from(result.wish, result.item, result.snapshot))
    }

    @PostMapping("/{wishId}/refresh")
    override fun refreshWishItem(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable wishId: Long,
    ): ApiResponseBody<WishItemResponse> {
        val result = wishlistService.refreshWishItem(userId = userId, wishId = wishId)
        return ApiResponseBody.ok(WishItemResponse.from(result.wish, result.item, result.snapshot))
    }

    @DeleteMapping("/{wishId}")
    override fun deleteWish(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable wishId: Long,
    ): ApiResponseBody<Unit> {
        wishlistService.deleteWish(userId = userId, wishId = wishId)
        return ApiResponseBody.ok()
    }

    // 다중 삭제는 의미상 DELETE 지만, DELETE + body 는 중간자(게이트웨이·LB·CDN)가 body 를 스트립/거절할 수 있어
    // (RFC 9110 은 DELETE body 의미를 정의하지 않음) id 목록을 query param(?ids=1,2,3)으로 받는다.
    // 누락 시 required=false + orEmpty 로 WishDeleteIds 검증(400)에 닿게 한다 — required=true 면 누락이
    // MissingServletRequestParameterException → 캐치올 500 으로 새기 때문이다(registerFromImages 의 교훈).
    @DeleteMapping
    override fun deleteWishes(
        @AuthenticationPrincipal userId: UUID,
        @RequestParam(name = "ids", required = false) ids: List<Long>?,
    ): ApiResponseBody<Unit> {
        wishlistService.deleteWishes(userId = userId, wishIds = WishDeleteIds.of(ids.orEmpty()))
        return ApiResponseBody.ok()
    }
}
