package com.depromeet.piki.support

import com.depromeet.piki.item.service.ImageParser

// ImageParser 를 래핑해 테스트별로 활성화/비활성화한다.
// StubLinkParser 와 동일 정책.
class StubImageParser(
    private val delegate: ImageParser,
) : ImageParser {
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
