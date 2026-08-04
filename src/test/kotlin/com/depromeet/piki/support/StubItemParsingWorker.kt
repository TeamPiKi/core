package com.depromeet.piki.support

import com.depromeet.piki.item.service.ItemParsingWorker
import com.depromeet.piki.product.domain.ProductLink

// ItemParsingWorker 를 래핑해 테스트별로 활성화/비활성화한다.
// enabled=true (기본): 실제 AsyncItemParsingWorker 에 위임 — WishlistRegisterAsyncIntegrationTest 처럼
//   실제 상태 전이를 검증하는 테스트는 그대로 동작한다.
// enabled=false: no-op — @Transactional 통합 테스트에서 미커밋 item 에 접근해 warn 로그가 찍히는
//   노이즈를 없애려면 해당 테스트 본문에서 false 로 설정한다.
class StubItemParsingWorker(private val delegate: ItemParsingWorker) : ItemParsingWorker {
    @Volatile var enabled: Boolean = true

    override fun parse(
        itemId: Long,
        snapshotId: Long,
        link: ProductLink,
        attempt: Int,
    ) {
        if (enabled) delegate.parse(itemId, snapshotId, link, attempt)
    }
}
