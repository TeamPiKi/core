package com.depromeet.piki.folder.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.folder.controller.dto.FolderMoveRequest
import com.depromeet.piki.folder.controller.dto.FolderMoveResponse
import com.depromeet.piki.folder.controller.dto.FolderNameRequest
import com.depromeet.piki.folder.controller.dto.FolderResponse
import com.depromeet.piki.folder.controller.dto.FolderWishCountResponse
import com.depromeet.piki.folder.controller.dto.FolderWishesRequest
import com.depromeet.piki.folder.domain.FolderWishIds
import com.depromeet.piki.folder.service.FolderService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/folders")
class FolderController(
    private val folderService: FolderService,
) : FolderApi {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(
        @AuthenticationPrincipal userId: UUID,
        @RequestBody request: FolderNameRequest,
    ): ApiResponseBody<FolderResponse> {
        val result = folderService.create(userId, request.toFolderName())
        return ApiResponseBody.created(FolderResponse.from(result))
    }

    @GetMapping
    override fun getFolders(
        @AuthenticationPrincipal userId: UUID,
    ): ApiResponseBody<List<FolderResponse>> = ApiResponseBody.ok(folderService.getFolders(userId).map(FolderResponse::from))

    @PatchMapping("/{folderId}")
    override fun rename(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable folderId: Long,
        @RequestBody request: FolderNameRequest,
    ): ApiResponseBody<FolderResponse> {
        val result = folderService.rename(userId, folderId, request.toFolderName())
        return ApiResponseBody.ok(FolderResponse.from(result))
    }

    @DeleteMapping("/{folderId}")
    override fun delete(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable folderId: Long,
    ): ApiResponseBody<Unit> {
        folderService.delete(userId, folderId)
        return ApiResponseBody.ok()
    }

    @PostMapping("/{folderId}/wishes")
    override fun putWishes(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable folderId: Long,
        @RequestBody request: FolderWishesRequest,
    ): ApiResponseBody<FolderWishCountResponse> {
        val result = folderService.putWishes(userId, folderId, request.toFolderWishIds())
        return ApiResponseBody.ok(FolderWishCountResponse.from(result))
    }

    // DELETE body 는 게이트웨이·CDN 이 버릴 수 있어 query 로 받는다(위시 다건 삭제와 같음). required=true 면 누락이 캐치올 500 으로 샌다
    @DeleteMapping("/{folderId}/wishes")
    override fun takeOutWishes(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable folderId: Long,
        @RequestParam(name = "wishIds", required = false) wishIds: List<Long>?,
    ): ApiResponseBody<FolderWishCountResponse> {
        val result = folderService.takeOutWishes(userId, folderId, FolderWishIds.of(wishIds))
        return ApiResponseBody.ok(FolderWishCountResponse.from(result))
    }

    @PostMapping("/{folderId}/wishes/move")
    override fun moveWishes(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable folderId: Long,
        @RequestBody request: FolderMoveRequest,
    ): ApiResponseBody<FolderMoveResponse> {
        val result = folderService.moveWishes(userId, folderId, request.toTargetFolderId(), request.toFolderWishIds())
        return ApiResponseBody.ok(FolderMoveResponse.from(result))
    }
}
