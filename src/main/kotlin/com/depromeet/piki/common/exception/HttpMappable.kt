package com.depromeet.piki.common.exception

import org.springframework.http.HttpStatus

interface HttpMappable {
    val httpStatus: HttpStatus
    val category: ErrorCategory

    // code 를 배정한 예외만 override 한다. 아직 이관 전인 예외는 null → 응답 code 도 null(비파괴 expand).
    val errorCode: ErrorCode? get() = null
}
