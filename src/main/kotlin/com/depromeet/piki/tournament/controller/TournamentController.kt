package com.depromeet.piki.tournament.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.tournament.controller.dto.CreateTournamentRequest
import com.depromeet.piki.tournament.controller.dto.CreateTournamentResponse
import com.depromeet.piki.tournament.controller.dto.GroupResultResponse
import com.depromeet.piki.tournament.controller.dto.JoinTournamentAsGuestRequest
import com.depromeet.piki.tournament.controller.dto.JoinTournamentAsGuestResponse
import com.depromeet.piki.tournament.controller.dto.UpdateInviteDurationRequest
import com.depromeet.piki.tournament.controller.dto.JoinTournamentRequest
import com.depromeet.piki.tournament.controller.dto.PlayLinkInfoResponse
import com.depromeet.piki.tournament.controller.dto.RecordMatchRequest
import com.depromeet.piki.tournament.controller.dto.RecordMatchResponse
import com.depromeet.piki.tournament.controller.dto.TournamentDetailResponse
import com.depromeet.piki.tournament.controller.dto.TournamentInvitePreviewResponse
import com.depromeet.piki.tournament.controller.dto.TournamentStartResponse
import com.depromeet.piki.tournament.controller.dto.TournamentSummaryResponse
import com.depromeet.piki.tournament.domain.TournamentPlayType
import com.depromeet.piki.tournament.domain.TournamentStatus
import com.depromeet.piki.tournament.service.TournamentInviteService
import com.depromeet.piki.tournament.service.TournamentService
import jakarta.validation.Valid
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
import java.time.LocalDateTime
import java.util.UUID

@RestController
@RequestMapping("/api/v1/tournaments")
class TournamentController(
    private val tournamentService: TournamentService,
    private val tournamentInviteService: TournamentInviteService,
) : TournamentApi {

    @GetMapping
    override fun getTournaments(
        @AuthenticationPrincipal userId: UUID,
        @RequestParam(required = false) status: List<TournamentStatus>?,
        @RequestParam(required = false) playType: TournamentPlayType?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponseBody<List<TournamentSummaryResponse>> {
        val summaries = tournamentService.getTournaments(userId, status, playType, limit)
        return ApiResponseBody.ok(summaries.map(TournamentSummaryResponse::from))
    }

    @GetMapping("/{tournamentId}")
    override fun getTournamentById(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable tournamentId: Long,
    ): ApiResponseBody<TournamentDetailResponse> {
        val detail = tournamentService.getTournamentById(tournamentId, userId)
        return ApiResponseBody.ok(TournamentDetailResponse.from(detail))
    }

    @GetMapping("/{tournamentId}/invite-preview")
    override fun getInvitePreview(
        // preview 는 permitAll — 토큰이 있으면 principal 이 채워지고, 없으면 null.
        @AuthenticationPrincipal userId: UUID?,
        @PathVariable tournamentId: Long,
    ): ApiResponseBody<TournamentInvitePreviewResponse> {
        val preview = tournamentService.getInvitePreview(tournamentId, userId)
        return ApiResponseBody.ok(TournamentInvitePreviewResponse.from(preview))
    }

    @GetMapping("/by-invite-code")
    override fun getInvitePreviewByCode(
        @AuthenticationPrincipal userId: UUID?,
        @RequestParam code: String,
    ): ApiResponseBody<TournamentInvitePreviewResponse> {
        val preview = tournamentService.getInvitePreviewByCode(code, userId)
        return ApiResponseBody.ok(TournamentInvitePreviewResponse.from(preview))
    }

    @GetMapping("/{tournamentId}/play-link-info")
    override fun getPlayLinkInfo(
        @PathVariable tournamentId: Long,
    ): ApiResponseBody<PlayLinkInfoResponse> {
        val info = tournamentService.getPlayLinkInfo(tournamentId)
        return ApiResponseBody.ok(PlayLinkInfoResponse.from(info))
    }

    @GetMapping("/{tournamentId}/group-result")
    override fun getGroupResult(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable tournamentId: Long,
    ): ApiResponseBody<GroupResultResponse> {
        val result = tournamentService.getGroupResult(userId, tournamentId)
        return ApiResponseBody.ok(GroupResultResponse.from(result))
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(
        @AuthenticationPrincipal userId: UUID,
        @Valid @RequestBody request: CreateTournamentRequest,
    ): ApiResponseBody<CreateTournamentResponse> {
        val result = tournamentService.create(userId, request.toCreateTournament())
        return ApiResponseBody.created(CreateTournamentResponse.from(result))
    }

    @PostMapping("/{tournamentId}/start")
    override fun start(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable tournamentId: Long,
    ): ApiResponseBody<TournamentStartResponse> {
        val result = tournamentService.start(userId, tournamentId)
        return ApiResponseBody.ok(TournamentStartResponse.from(result))
    }

    @PostMapping("/{tournamentId}/join")
    override fun join(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable tournamentId: Long,
        @Valid @RequestBody request: JoinTournamentRequest,
    ): ApiResponseBody<Unit> {
        tournamentInviteService.join(userId, tournamentId, request.inviteCode)
        return ApiResponseBody.ok()
    }

    @PostMapping("/{tournamentId}/join/guest")
    @ResponseStatus(HttpStatus.CREATED)
    override fun joinAsGuest(
        @PathVariable tournamentId: Long,
        @Valid @RequestBody request: JoinTournamentAsGuestRequest,
    ): ApiResponseBody<JoinTournamentAsGuestResponse> {
        val result = tournamentInviteService.joinAsGuest(tournamentId, request.inviteCode, request.nickname)
        return ApiResponseBody.created(JoinTournamentAsGuestResponse.from(result))
    }

    @PostMapping("/{tournamentId}/matches")
    override fun recordMatch(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable tournamentId: Long,
        @Valid @RequestBody request: RecordMatchRequest,
    ): ApiResponseBody<RecordMatchResponse> {
        val result = tournamentService.recordMatch(userId, request.toRecordMatch(tournamentId))
        return ApiResponseBody.ok(RecordMatchResponse.from(result))
    }

    @PostMapping("/{tournamentId}/play-link")
    override fun createPlayLink(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable tournamentId: Long,
    ): ApiResponseBody<LocalDateTime> {
        val expiresAt = tournamentService.createPlayLink(userId, tournamentId)
        return ApiResponseBody.ok(expiresAt)
    }

    @PostMapping("/{sourceTournamentId}/from-play-link")
    override fun createFromPlayLink(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable sourceTournamentId: Long,
    ): ApiResponseBody<Long> {
        // idempotent get-or-create: 신규 생성·기존 클론 반환 모두 200. 항상 "생성"이 아니므로 201 을 쓰지 않는다.
        val tournamentId = tournamentService.createFromPlayLink(userId, sourceTournamentId)
        return ApiResponseBody.ok(tournamentId)
    }

    @PatchMapping("/{tournamentId}/invite")
    override fun updateInviteExpiry(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable tournamentId: Long,
        @Valid @RequestBody request: UpdateInviteDurationRequest,
    ): ApiResponseBody<LocalDateTime> {
        val newExpiresAt = tournamentService.updateInviteExpiry(userId, tournamentId, request.newExpiresAt)
        return ApiResponseBody.ok(newExpiresAt)
    }

    @DeleteMapping("/{tournamentId}")
    override fun deleteTournament(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable tournamentId: Long,
    ): ApiResponseBody<Unit> {
        tournamentService.deleteTournament(userId, tournamentId)
        return ApiResponseBody.ok()
    }
}
