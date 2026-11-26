package com.depromeet.piki.explore.service

import com.depromeet.piki.explore.domain.ExploreFeed
import com.depromeet.piki.explore.repository.ExploreFeedStore
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class ExploreFeedRefresher(
    private val exploreFeedBuilder: ExploreFeedBuilder,
    private val exploreFeedStore: ExploreFeedStore,
) {
    // 부분 수정 없이 매번 통째로 다시 만든다. 놓친 변경이 있어도 다음 주기에 맞춰진다
    @Scheduled(fixedDelay = REFRESH_INTERVAL_MS)
    fun refresh() {
        exploreFeedStore.save(exploreFeedBuilder.build())
    }

    companion object {
        // ExploreFeedStore 의 TTL 보다 짧아야 키가 비는 구간이 없다
        private const val REFRESH_INTERVAL_MS = 60_000L
    }
}
