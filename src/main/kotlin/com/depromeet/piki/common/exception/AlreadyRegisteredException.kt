package com.depromeet.piki.common.exception

import org.springframework.http.HttpStatus

// 이미 담은 것을 또 담으려는 요청(#973). 위시의 같은 상품, 토너먼트의 같은 링크가 여기로 온다.
// 사유가 도메인마다 달라 문구·code 는 도메인이 넘기고, "무엇과 겹쳤는지" 만 이 클래스가 공통으로 나른다.
//
// 별도 클래스로 둔 이유는 ItemQuotaException 과 같다 — 겹친 리소스를 응답에 실어야 하는 사유는 도메인당
// 하나뿐인데, 그것 때문에 WishException·TournamentException 에 nullable payload 를 달면 그 클래스의
// 나머지 20여 사유가 전부 쓰지 않는 필드를 떠안고 ErrorPayload 구현 여부가 아무것도 가리지 못하게 된다.
// 클래스를 나누면 payload 가 non-null 이 되어 RetryAfter 와 같은 결로 "구현 여부 = 데이터 유무" 가 성립한다.
class AlreadyRegisteredException private constructor(
    override val errorCode: ErrorCode,
    override val payload: Any,
) : BaseException(errorCode.message),
    HttpMappable,
    ErrorPayload {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        // 이미 위시에 담긴 상품. 응답 data 로 그 위시의 id 를 함께 내려, 클라가 목록을 다시 조회하지 않고
        // "이미 담았어요, 보러가기" 를 그릴 수 있게 한다.
        fun wish(
            errorCode: ErrorCode,
            wishId: Long,
        ): AlreadyRegisteredException = AlreadyRegisteredException(errorCode, ExistingWish(wishId))

        // 이미 출전 중인 아이템. 위와 같은 결로 그 tournament_item id 를 내린다.
        fun tournamentItem(
            errorCode: ErrorCode,
            tournamentItemId: Long,
        ): AlreadyRegisteredException = AlreadyRegisteredException(errorCode, ExistingTournamentItem(tournamentItemId))
    }
}

// 응답 data 로 그대로 직렬화되는 값들 — 필드명이 곧 클라 계약이라 프로퍼티명에서 나오게 둔다
// (문자열 키 맵이었다면 그 이름이 코드 어디에도 묶이지 않아 rename 이 조용히 계약을 깬다).
data class ExistingWish(
    val wishId: Long,
)

data class ExistingTournamentItem(
    val tournamentItemId: Long,
)
