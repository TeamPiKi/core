package com.depromeet.piki.tournament.service

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

class TournamentException private constructor(
    message: String,
    override val category: ErrorCategory,
    override val httpStatus: HttpStatus,
) : BaseException(message),
    HttpMappable {
    companion object {
        fun forbiddenTournament(): TournamentException =
            TournamentException(
                "이 토너먼트에 접근할 수 없어요.",
                ErrorCategory.FORBIDDEN,
                HttpStatus.FORBIDDEN,
            )

        fun notFoundTournament(): TournamentException =
            TournamentException(
                "토너먼트를 찾을 수 없어요. 이미 삭제됐을 수 있어요.",
                ErrorCategory.NOT_FOUND,
                HttpStatus.NOT_FOUND,
            )

        fun invalidWinner(): TournamentException =
            TournamentException(
                "올바르지 않은 선택이에요.",
                ErrorCategory.INVALID_INPUT,
                HttpStatus.BAD_REQUEST,
            )

        // 목록 조회 limit 이 1 미만(0·음수)인 경우. 정상 클라이언트는 limit>=1(홈은 3)만 보내므로,
        // 여기 닿는 건 잘못 구성한 요청 = 계약 위반 → 400. (#728 tournament code 이관 시 다른 팩토리와 함께 code 부여.)
        fun invalidLimit(): TournamentException =
            TournamentException(
                "조회 개수는 1 이상이어야 해요.",
                ErrorCategory.INVALID_INPUT,
                HttpStatus.BAD_REQUEST,
            )

        fun invalidTournamentItem(): TournamentException =
            TournamentException(
                "이 토너먼트에 없는 아이템이에요.",
                ErrorCategory.INVALID_INPUT,
                HttpStatus.BAD_REQUEST,
            )

        fun notPendingTournament(): TournamentException =
            TournamentException(
                "토너먼트가 시작되기 전에만 할 수 있어요.",
                ErrorCategory.CONFLICT,
                HttpStatus.CONFLICT,
            )

        fun notInProgressTournament(): TournamentException =
            TournamentException(
                "토너먼트가 진행 중일 때만 할 수 있어요.",
                ErrorCategory.CONFLICT,
                HttpStatus.CONFLICT,
            )

        fun invalidItemCount(): TournamentException =
            TournamentException(
                "아이템은 2~32개 사이로 담아주세요.",
                ErrorCategory.INVALID_INPUT,
                HttpStatus.BAD_REQUEST,
            )

        fun tooManyTournamentItems(): TournamentException =
            TournamentException(
                "아이템은 최대 32개까지 담을 수 있어요.",
                ErrorCategory.INVALID_INPUT,
                HttpStatus.BAD_REQUEST,
            )

        fun duplicateTournamentItem(): TournamentException =
            TournamentException(
                "이미 담은 아이템이에요.",
                ErrorCategory.CONFLICT,
                HttpStatus.CONFLICT,
            )

        fun notFoundTournamentItem(): TournamentException =
            TournamentException(
                "토너먼트 아이템을 찾을 수 없어요.",
                ErrorCategory.NOT_FOUND,
                HttpStatus.NOT_FOUND,
            )

        fun notFoundItems(): TournamentException =
            TournamentException(
                "존재하지 않는 아이템이 포함되어 있어요.",
                ErrorCategory.NOT_FOUND,
                HttpStatus.NOT_FOUND,
            )

        // 비동기 파싱이 끝나지 않은(PENDING·PROCESSING) 또는 실패한(FAILED) 상품을 위시에서 토너먼트에 추가하려 한 경우.
        // 곧 READY 가 되거나 영구 실패라 현재 상태와 충돌 → 409.
        fun itemNotReady(): TournamentException =
            TournamentException(
                "아직 정보를 가져오는 중인 상품이에요. 잠시 후 추가해 주세요.",
                ErrorCategory.CONFLICT,
                HttpStatus.CONFLICT,
            )

        // 토너먼트 시작 시 PENDING·PROCESSING·FAILED 아이템이 포함된 경우.
        fun itemNotReadyToStart(): TournamentException =
            TournamentException(
                "아직 준비 중인 상품이 있어요. 모두 준비되면 시작할 수 있어요.",
                ErrorCategory.CONFLICT,
                HttpStatus.CONFLICT,
            )

        // 토너먼트 시작 시 가격 정보가 없는 아이템이 포함된 경우.
        // 가격 기준 정렬이 불가능하고 클라이언트가 아이템 추가 시점에 해당 상태를 유발할 수 있어 → 409.
        fun itemPriceRequired(): TournamentException =
            TournamentException(
                "가격 정보가 없는 상품이 있어요. 직접 입력 후 시작해 주세요.",
                ErrorCategory.CONFLICT,
                HttpStatus.CONFLICT,
            )

        fun invalidImageCount(): TournamentException =
            TournamentException(
                "이미지는 1~5장만 올릴 수 있어요.",
                ErrorCategory.INVALID_INPUT,
                HttpStatus.BAD_REQUEST,
            )

        fun itemNotInWishlist(): TournamentException =
            TournamentException(
                "위시에 저장된 아이템만 담을 수 있어요.",
                ErrorCategory.FORBIDDEN,
                HttpStatus.FORBIDDEN,
            )

        fun eliminatedTournamentItem(): TournamentException =
            TournamentException(
                "이미 탈락한 아이템이에요.",
                ErrorCategory.CONFLICT,
                HttpStatus.CONFLICT,
            )

        fun invalidCurrentRound(): TournamentException =
            TournamentException(
                "지금은 진행할 수 없는 단계예요.",
                ErrorCategory.INVALID_INPUT,
                HttpStatus.BAD_REQUEST,
            )

        fun inProgressTournamentCannotBeDeleted(): TournamentException =
            TournamentException(
                "토너먼트가 끝난 후 삭제할 수 있어요.",
                ErrorCategory.CONFLICT,
                HttpStatus.CONFLICT,
            )

        fun invalidInviteCode(): TournamentException =
            TournamentException(
                "초대 코드가 올바르지 않아요.",
                ErrorCategory.INVALID_INPUT,
                HttpStatus.BAD_REQUEST,
            )

        fun inviteExpired(): TournamentException =
            TournamentException(
                "초대 링크가 만료됐어요.",
                ErrorCategory.CONFLICT,
                HttpStatus.CONFLICT,
            )

        fun alreadyParticipant(): TournamentException =
            TournamentException(
                "이미 참여 중인 토너먼트예요.",
                ErrorCategory.CONFLICT,
                HttpStatus.CONFLICT,
            )

        fun notCompletedTournament(): TournamentException =
            TournamentException(
                "완료된 토너먼트에서만 할 수 있어요.",
                ErrorCategory.CONFLICT,
                HttpStatus.CONFLICT,
            )

        fun clonedTournamentCannotSharePlayLink(): TournamentException =
            TournamentException(
                "플레이 링크로 참여한 토너먼트는 공유 링크를 만들 수 없어요.",
                ErrorCategory.FORBIDDEN,
                HttpStatus.FORBIDDEN,
            )

        fun playLinkAlreadyCreated(): TournamentException =
            TournamentException(
                "이미 플레이 링크가 만들어진 토너먼트예요.",
                ErrorCategory.CONFLICT,
                HttpStatus.CONFLICT,
            )

        fun playLinkNotCreated(): TournamentException =
            TournamentException(
                "아직 플레이 링크가 만들어지지 않은 토너먼트예요.",
                ErrorCategory.NOT_FOUND,
                HttpStatus.NOT_FOUND,
            )

        fun playLinkExpired(): TournamentException =
            TournamentException(
                "플레이 링크가 만료됐어요.",
                ErrorCategory.CONFLICT,
                HttpStatus.CONFLICT,
            )

        fun groupResultNotAvailable(): TournamentException =
            TournamentException(
                "완료된 토너먼트에서만 결과를 볼 수 있어요.",
                ErrorCategory.CONFLICT,
                HttpStatus.CONFLICT,
            )

        fun alreadyCloned(): TournamentException =
            TournamentException(
                "이미 이 플레이 링크로 만든 토너먼트가 있어요.",
                ErrorCategory.CONFLICT,
                HttpStatus.CONFLICT,
            )

        fun participantLimitExceeded(): TournamentException =
            TournamentException(
                "참여 인원이 가득 찼어요.",
                ErrorCategory.CONFLICT,
                HttpStatus.CONFLICT,
            )

        fun clonedTournamentCannotViewGroupResult(): TournamentException =
            TournamentException(
                "플레이 링크로 참여한 토너먼트에서는 친구 결과를 볼 수 없어요.",
                ErrorCategory.FORBIDDEN,
                HttpStatus.FORBIDDEN,
            )

        fun clonedTournamentCannotAddItems(): TournamentException =
            TournamentException(
                "플레이 링크로 만든 토너먼트에는 아이템을 추가할 수 없어요.",
                ErrorCategory.FORBIDDEN,
                HttpStatus.FORBIDDEN,
            )
    }
}
