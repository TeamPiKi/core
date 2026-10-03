package com.depromeet.piki.item.service

// runCatching 은 Error 까지 삼킨다. Error 를 추출 실패로 세면 이미지 원본이 지워지고 망가진 프로세스가 계속 작업을 집는다(#941).
internal inline fun <T> runCatchingException(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: Exception) {
        Result.failure(e)
    }
