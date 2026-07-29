package com.depromeet.piki.item.service

import com.depromeet.piki.product.domain.ProductLink

// 등록 직후 PROCESSING item 의 파싱을 백그라운드로 수행하는 경계.
// 호출 즉시 반환하고 실제 파싱(외부 LLM 호출)은 비동기로 진행되어 item 을 READY/FAILED 로 전이시킨다.
// 구현(@Async)을 인터페이스 뒤에 두어, 추후 메시지 큐 기반 워커로 교체하더라도
// 호출부(WishlistService)가 바뀌지 않게 한다.
interface ItemParsingWorker {
    // expectedAttempt 는 소유권을 획득할 때 기대하는 **직전** attemptCount(ClaimedItem.expectedAttempt)다.
    // 워커는 이 값으로 조건부 +1 을 시도해 소유권을 얻고, 그렇게 얻은 토큰(expectedAttempt + 1)을 박동·전이에 실어 좀비 결과를 걸러낸다.
    fun parse(
        itemId: Long,
        snapshotId: Long,
        link: ProductLink,
        expectedAttempt: Int,
    )
}
