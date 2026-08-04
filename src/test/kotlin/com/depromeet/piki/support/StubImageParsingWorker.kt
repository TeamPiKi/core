package com.depromeet.piki.support

import com.depromeet.piki.item.service.ImageParsingWorker

// ImageParsingWorker 를 래핑해 테스트별로 활성화/비활성화한다.
// StubItemParsingWorker 와 동일 정책.
class StubImageParsingWorker(
    private val delegate: ImageParsingWorker,
) : ImageParsingWorker {
    @Volatile var enabled: Boolean = true

    override fun parse(
        itemId: Long,
        snapshotId: Long,
        imageKey: String,
        attempt: Int,
    ) {
        if (enabled) delegate.parse(itemId, snapshotId, imageKey, attempt)
    }
}
