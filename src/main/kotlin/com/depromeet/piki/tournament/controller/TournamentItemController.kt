package com.depromeet.piki.tournament.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.image.controller.dto.ConfirmImageUploadRequest
import com.depromeet.piki.image.controller.dto.PresignedImageUploadRequest
import com.depromeet.piki.image.controller.dto.PresignedImageUploadResponse
import com.depromeet.piki.tournament.controller.dto.AddTournamentItemFromLinkRequest
import com.depromeet.piki.tournament.controller.dto.AddTournamentItemFromLinkResponse
import com.depromeet.piki.tournament.controller.dto.AddTournamentItemsFromImagesResponse
import com.depromeet.piki.tournament.controller.dto.AddTournamentItemsFromWishResponse
import com.depromeet.piki.tournament.controller.dto.AddTournamentItemsRequest
import com.depromeet.piki.tournament.controller.dto.TournamentItemDetailResponse
import com.depromeet.piki.tournament.controller.dto.UpdateTournamentItemRequest
import com.depromeet.piki.tournament.service.TournamentItemService
import com.depromeet.piki.tournament.service.TournamentService
import jakarta.validation.Valid
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
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/tournaments")
class TournamentItemController(
    private val tournamentService: TournamentService,
    private val tournamentItemService: TournamentItemService,
) : TournamentItemApi {
    @GetMapping("/{tournamentId}/items/{tournamentItemId}")
    override fun getTournamentItem(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable tournamentId: Long,
        @PathVariable tournamentItemId: Long,
    ): ApiResponseBody<TournamentItemDetailResponse> {
        val detail = tournamentService.getTournamentItem(userId, tournamentId, tournamentItemId)
        return ApiResponseBody.ok(TournamentItemDetailResponse.from(detail))
    }

    @PostMapping("/{tournamentId}/items/wish")
    override fun addItemsFromWish(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable tournamentId: Long,
        @Valid @RequestBody request: AddTournamentItemsRequest,
    ): ApiResponseBody<AddTournamentItemsFromWishResponse> {
        val tournamentItemIds = tournamentService.addItemsFromWish(userId, request.toAddTournamentItemsFromWish(tournamentId))
        return ApiResponseBody.ok(AddTournamentItemsFromWishResponse(tournamentItemIds))
    }

    @PostMapping("/{tournamentId}/items/link")
    override fun addItemFromLink(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable tournamentId: Long,
        @Valid @RequestBody request: AddTournamentItemFromLinkRequest,
    ): ApiResponseBody<AddTournamentItemFromLinkResponse> {
        val tournamentItemId = tournamentItemService.addItemFromLink(userId, tournamentId, request.url)
        return ApiResponseBody.ok(AddTournamentItemFromLinkResponse(tournamentItemId))
    }

    // 이미지 등록 v2 1단계 — presigned 발급. pending_uploads 에 발급 기록만 남기고 tournament_item 은 아직 만들지 않으므로 200 OK.
    @PostMapping("/{tournamentId}/items/images/presigned")
    override fun presignImageUploads(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable tournamentId: Long,
        @RequestBody request: PresignedImageUploadRequest,
    ): ApiResponseBody<PresignedImageUploadResponse> {
        val uploads = tournamentItemService.presignImageUploads(userId, tournamentId, request.contentTypes)
        return ApiResponseBody.ok(PresignedImageUploadResponse.from(uploads))
    }

    // 이미지 등록 2단계 — 업로드 확정. 아이템이 실제로 추가되며 tournamentItemIds 를 200 으로 돌려준다.
    @PostMapping("/{tournamentId}/items/images/confirm")
    override fun confirmImageRegistration(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable tournamentId: Long,
        @RequestBody request: ConfirmImageUploadRequest,
    ): ApiResponseBody<AddTournamentItemsFromImagesResponse> {
        val tournamentItemIds = tournamentItemService.confirmImageRegistration(userId, tournamentId, request.imageKeys)
        return ApiResponseBody.ok(AddTournamentItemsFromImagesResponse(tournamentItemIds))
    }

    @PatchMapping("/{tournamentId}/items/{tournamentItemId}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    override fun updateItem(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable tournamentId: Long,
        @PathVariable tournamentItemId: Long,
        @Valid @ModelAttribute request: UpdateTournamentItemRequest,
    ): ApiResponseBody<Unit> {
        tournamentItemService.updateItem(userId, tournamentId, tournamentItemId, request.name, request.price, request.currency, request.image)
        return ApiResponseBody.ok()
    }

    @DeleteMapping("/{tournamentId}/items/{tournamentItemId}")
    override fun deleteItem(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable tournamentId: Long,
        @PathVariable tournamentItemId: Long,
    ): ApiResponseBody<Unit> {
        tournamentService.deleteItem(userId, tournamentId, tournamentItemId)
        return ApiResponseBody.ok()
    }
}
