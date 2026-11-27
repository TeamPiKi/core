package com.depromeet.piki.explore.service

import com.depromeet.piki.user.event.UserProfileHidden
import com.depromeet.piki.user.event.UserWithdrawn
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

// 숨기는 변경은 다음 주기를 기다리지 않고 바로 피드에서 뺀다. 드러내는 변경은 다음 주기에 맡긴다
@Component
class ExploreFeedHideListener(
    private val exploreFeedRefresher: ExploreFeedRefresher,
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onProfileHidden(event: UserProfileHidden) = refreshNow()

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onWithdrawn(event: UserWithdrawn) = refreshNow()

    // 실패는 refresh 가 피드마다 삼키고 남긴다. 사용자의 요청은 이미 커밋됐고 다음 주기가 맞춘다
    private fun refreshNow() = exploreFeedRefresher.refresh()
}
