package com.depromeet.piki.explore.service

import com.depromeet.piki.explore.domain.ExploreFeed
import com.depromeet.piki.explore.repository.ExploreFeedStore
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class ExploreFeedRefresher(
    private val exploreFeedBuilder: ExploreFeedBuilder,
    private val explorePickFeedBuilder: ExplorePickFeedBuilder,
    private val exploreFeedStore: ExploreFeedStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 부분 수정 없이 매번 통째로 다시 만든다. 놓친 변경이 있어도 다음 주기에 맞춰진다.
    // 한 피드가 실패해도 다른 피드는 갱신한다
    @Scheduled(fixedDelay = REFRESH_INTERVAL_MS)
    fun refresh() {
        runCatching { exploreFeedStore.save(exploreFeedBuilder.build()) }
            .onFailure { log.warn("방금 담긴 위시 피드 재생성 실패", it) }
        runCatching { exploreFeedStore.savePicks(explorePickFeedBuilder.build()) }
            .onFailure { log.warn("1위 피드 재생성 실패", it) }
    }

    companion object {
        // ExploreFeedStore 의 TTL 보다 짧아야 키가 비는 구간이 없다
        private const val REFRESH_INTERVAL_MS = 60_000L
    }
}
