package com.depromeet.piki.common.ratelimit

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.CommonErrorCode
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.HttpMappable
import com.depromeet.piki.common.exception.RetryAfter
import org.springframework.http.HttpStatus

// 아이템 등록이 한도에 걸린 경우. 클라이언트가 정상 요청으로 닿을 수 있는 계약 응답이라 커스텀 예외다.
// 사유가 둘이고 status 가 갈린다 — 요청자 몫 소진은 429(#339), 서비스 전체 가용량 소진은 503(#927).
// status 를 여기서 들지 않고 category 에서 파생하므로 팩토리가 고른 code 하나로 둘 다 정해진다.
//
// errorCode 를 생성자로 받는 이유: 사유 문구와 code 는 도메인이 소유해야 한다(위시가 막힌 것과 토너먼트가
// 막힌 것은 사용자에게 다른 문구여야 하고, 토너먼트 쪽은 오너의 사용량이라는 사실을 요청자에게 노출하면 안 된다).
// 그렇다고 도메인마다 예외 클래스를 늘리면 RetryAfter 를 구현하는 클래스가 도메인 수만큼 생기고, 그 클래스의
// 나머지 사유들까지 재시도 시점을 들어야 하는 nullable 필드를 떠안는다. 그래서 클래스는 여기 하나로 두고
// code·문구만 도메인이 넘긴다.
class ItemQuotaException private constructor(
    override val errorCode: ErrorCode,
    override val retryAfterSeconds: Long,
) : BaseException(errorCode.message),
    HttpMappable,
    RetryAfter {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        // retryAfterSeconds 는 창이 리셋되기까지 남은 시간이다. 숫자만 받으므로 응답 detail 에 내부 정보가 실리지 않는다
        // (문구는 errorCode 가 고정으로 소유한다 — 임의 문자열을 message 에 싣는 팩토리를 두지 않는 이유).
        fun exceeded(
            errorCode: ErrorCode,
            retryAfterSeconds: Long,
        ): ItemQuotaException {
            require(errorCode.category == ErrorCategory.TOO_MANY_REQUESTS) {
                "한도 초과 예외의 category 는 TOO_MANY_REQUESTS 여야 한다: ${errorCode.code} → ${errorCode.category}"
            }
            // 0 이면 클라가 즉시 재시도해 또 거부되고, 음수는 Retry-After 로 나갈 수 없는 값이다.
            // 현재 유일한 호출자(RedisItemQuotaStore)가 최소 1초를 보장하지만 그건 그쪽 사정이라,
            // 이 팩토리로 만드는 예외는 어느 호출자가 오든 유효한 재시도 시점을 갖도록 여기서 못박는다.
            require(retryAfterSeconds > 0) { "재시도 시점($retryAfterSeconds)은 양수여야 한다." }
            return ItemQuotaException(errorCode, retryAfterSeconds)
        }

        // 전역 가용량 소진(#927). 요청자의 몫과 무관하게 **서비스가 꽉 찬** 상태라 4xx 가 아니라 503 이다.
        //
        // code 를 도메인이 넘기지 않고 공통 SERVER_BUSY 로 고정하는 이유: 위 exceeded 는 "위시가 막혔나
        // 토너먼트가 막혔나" 로 사용자에게 다른 문구를 줘야 해서 도메인이 code 를 소유했지만, 이쪽은 어느
        // 등록 경로로 닿든 원인도 안내도 하나다("지금은 서비스가 바쁘다"). 도메인마다 같은 문구의 code 를
        // 늘리면 클라가 구분해 처리할 것도 없이 매핑 표만 길어진다.
        fun capacityExceeded(retryAfterSeconds: Long): ItemQuotaException {
            require(retryAfterSeconds > 0) { "재시도 시점($retryAfterSeconds)은 양수여야 한다." }
            return ItemQuotaException(CommonErrorCode.SERVER_BUSY, retryAfterSeconds)
        }
    }
}
