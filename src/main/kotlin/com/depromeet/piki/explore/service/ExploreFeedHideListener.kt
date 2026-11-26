package com.depromeet.piki.explore.service

import com.depromeet.piki.user.event.UserProfileHidden
import com.depromeet.piki.user.event.UserWithdrawn
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

// 숨기는 변경은 다음 주기를 기다리지 않고 바로 피드에서 뺀다. 드러내는 변경은 다음 주기에 맡긴다
@Component
class ExploreFeedHideListener(
    private val exploreFeedRefresher: ExploreFeedRefresher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onProfileHidden(event: UserProfileHidden) = refreshNow()

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onWithdrawn(event: UserWithdrawn) = refreshNow()

    // 여기서 실패해도 사용자의 요청은 이미 커밋됐다. 다음 주기의 재생성이 맞춘다
    private fun refreshNow() {
        runCatching { exploreFeedRefresher.refresh() }
            .onFailure { log.warn("탐색 피드 즉시 재생성 실패. 다음 주기에 반영된다: {}", it.message) }
    }
}
