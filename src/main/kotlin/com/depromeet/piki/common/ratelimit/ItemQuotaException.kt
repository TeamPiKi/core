package com.depromeet.piki.common.ratelimit

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.HttpMappable
import com.depromeet.piki.common.exception.RetryAfter
import org.springframework.http.HttpStatus

// 아이템 등록 한도 초과(#339). 클라이언트가 정상 요청으로 닿을 수 있는 계약 응답이라 커스텀 예외(429)다.
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
            return ItemQuotaException(errorCode, retryAfterSeconds)
        }
    }
}
