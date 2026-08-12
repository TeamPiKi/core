package com.depromeet.piki.tournament.service

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// message·category·httpStatus 는 전부 errorCode 하나에서 파생한다(TournamentErrorCode 가 single source).
class TournamentException private constructor(
    override val errorCode: ErrorCode,
) : BaseException(errorCode.message),
    HttpMappable {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        fun forbiddenTournament(): TournamentException = TournamentException(TournamentErrorCode.FORBIDDEN_TOURNAMENT)

        fun notFoundTournament(): TournamentException = TournamentException(TournamentErrorCode.NOT_FOUND_TOURNAMENT)

        fun invalidWinner(): TournamentException = TournamentException(TournamentErrorCode.INVALID_WINNER)

        // 목록 조회 limit 이 1 미만(0·음수)인 경우. 정상 클라이언트는 limit>=1(홈은 3)만 보내므로,
        // 여기 닿는 건 잘못 구성한 요청 = 계약 위반 → 400.
        fun invalidLimit(): TournamentException = TournamentException(TournamentErrorCode.INVALID_LIMIT)

        fun invalidTournamentItem(): TournamentException = TournamentException(TournamentErrorCode.INVALID_TOURNAMENT_ITEM)

        fun notPendingTournament(): TournamentException = TournamentException(TournamentErrorCode.NOT_PENDING_TOURNAMENT)

        fun notInProgressTournament(): TournamentException = TournamentException(TournamentErrorCode.NOT_IN_PROGRESS_TOURNAMENT)

        fun invalidItemCount(): TournamentException = TournamentException(TournamentErrorCode.INVALID_ITEM_COUNT)

        fun tooManyTournamentItems(): TournamentException = TournamentException(TournamentErrorCode.TOO_MANY_TOURNAMENT_ITEMS)

        fun duplicateTournamentItem(): TournamentException = TournamentException(TournamentErrorCode.DUPLICATE_TOURNAMENT_ITEM)

        fun notFoundTournamentItem(): TournamentException = TournamentException(TournamentErrorCode.NOT_FOUND_TOURNAMENT_ITEM)

        fun notFoundItems(): TournamentException = TournamentException(TournamentErrorCode.NOT_FOUND_ITEMS)

        // 비동기 파싱이 끝나지 않은(PENDING·PROCESSING) 또는 실패한(FAILED) 상품을 위시에서 토너먼트에 추가하려 한 경우.
        // 곧 READY 가 되거나 영구 실패라 현재 상태와 충돌 → 409.
        fun itemNotReady(): TournamentException = TournamentException(TournamentErrorCode.ITEM_NOT_READY)

        // 토너먼트 시작 시 PENDING·PROCESSING·FAILED 아이템이 포함된 경우.
        fun itemNotReadyToStart(): TournamentException = TournamentException(TournamentErrorCode.ITEM_NOT_READY_TO_START)

        // 토너먼트 시작 시 가격 정보가 없는 아이템이 포함된 경우.
        // 가격 기준 정렬이 불가능하고 클라이언트가 아이템 추가 시점에 해당 상태를 유발할 수 있어 → 409.
        fun itemPriceRequired(): TournamentException = TournamentException(TournamentErrorCode.ITEM_PRICE_REQUIRED)

        fun invalidImageCount(): TournamentException = TournamentException(TournamentErrorCode.INVALID_IMAGE_COUNT)

        fun itemNotInWishlist(): TournamentException = TournamentException(TournamentErrorCode.ITEM_NOT_IN_WISHLIST)

        fun eliminatedTournamentItem(): TournamentException = TournamentException(TournamentErrorCode.ELIMINATED_TOURNAMENT_ITEM)

        fun invalidCurrentRound(): TournamentException = TournamentException(TournamentErrorCode.INVALID_CURRENT_ROUND)

        fun inProgressTournamentCannotBeDeleted(): TournamentException =
            TournamentException(TournamentErrorCode.IN_PROGRESS_TOURNAMENT_CANNOT_BE_DELETED)

        fun invalidInviteCode(): TournamentException = TournamentException(TournamentErrorCode.INVALID_INVITE_CODE)

        fun inviteExpired(): TournamentException = TournamentException(TournamentErrorCode.INVITE_EXPIRED)

        fun alreadyParticipant(): TournamentException = TournamentException(TournamentErrorCode.ALREADY_PARTICIPANT)

        fun notCompletedTournament(): TournamentException = TournamentException(TournamentErrorCode.NOT_COMPLETED_TOURNAMENT)

        fun clonedTournamentCannotSharePlayLink(): TournamentException =
            TournamentException(TournamentErrorCode.CLONED_TOURNAMENT_CANNOT_SHARE_PLAY_LINK)

        fun playLinkAlreadyCreated(): TournamentException = TournamentException(TournamentErrorCode.PLAY_LINK_ALREADY_CREATED)

        fun playLinkNotCreated(): TournamentException = TournamentException(TournamentErrorCode.PLAY_LINK_NOT_CREATED)

        fun playLinkExpired(): TournamentException = TournamentException(TournamentErrorCode.PLAY_LINK_EXPIRED)

        fun groupResultNotAvailable(): TournamentException = TournamentException(TournamentErrorCode.GROUP_RESULT_NOT_AVAILABLE)

        fun alreadyCloned(): TournamentException = TournamentException(TournamentErrorCode.ALREADY_CLONED)

        fun participantLimitExceeded(): TournamentException = TournamentException(TournamentErrorCode.PARTICIPANT_LIMIT_EXCEEDED)

        fun clonedTournamentCannotViewGroupResult(): TournamentException =
            TournamentException(TournamentErrorCode.CLONED_TOURNAMENT_CANNOT_VIEW_GROUP_RESULT)

        fun clonedTournamentCannotAddItems(): TournamentException =
            TournamentException(TournamentErrorCode.CLONED_TOURNAMENT_CANNOT_ADD_ITEMS)

        // 서버가 브래킷에서 파생한 페어 집합에 없는 조합을 보낸 경우(#683). 최신 클라는 서버가 내려준
        // currentMatch·nextMatch 를 그대로 되돌려주므로, 여기 닿는 건 조합을 임의로 구성한 요청 = 계약 위반 → 400.
        fun invalidMatchPair(): TournamentException = TournamentException(TournamentErrorCode.INVALID_MATCH_PAIR)

        // 이미 기록된 매치에 다른 승자를 보낸 경우(#683). 같은 승자면 멱등 성공이고, 다른 승자는 결과 뒤집기라 → 409.
        fun matchAlreadyRecorded(): TournamentException = TournamentException(TournamentErrorCode.MATCH_ALREADY_RECORDED)

        // 토너먼트 만들기는 회원 전용(#339) — 게스트(인증은 됐으나 회원 아님)가 정상 요청으로 닿을 수 있는 계약 응답이라 커스텀 예외(403).
        // Security 에서 MEMBER 만 허용하면 detail 없는 권한 없음 403 으로 떨어져 "회원 전용" 사유를 못 전달하므로,
        // authenticated() 로 통과시킨 뒤 서비스가 이 예외로 막는다(WishException.guestCannotUseWishlist 와 같은 패턴).
        fun guestCannotCreateTournament(): TournamentException = TournamentException(TournamentErrorCode.GUEST_CANNOT_CREATE_TOURNAMENT)
    }
}
