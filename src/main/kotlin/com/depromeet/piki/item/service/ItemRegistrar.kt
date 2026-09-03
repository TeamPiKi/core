package com.depromeet.piki.item.service

import com.depromeet.piki.common.ratelimit.ItemQuotaGuard
import com.depromeet.piki.item.domain.ItemErrorCode
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.routing.DomainAccessPolicy
import org.springframework.stereotype.Component
import java.util.UUID

// 이 링크를 아이템으로 받아들여도 되는지 판정하고, 통과하면 한 개 몫을 확보한다.
// 위시·토너먼트가 각자 베껴 쓰던 두 줄을 한 자리로 모은 것이다.
//
// 정책 위반은 차감 앞에서 걸러야 한다 - 뒤로 가면 등록되지도 않을 요청이 사용자 몫을 깎는다(#973).
// 중복 판정도 같은 이유로 차감 앞이지만 기준이 도메인마다 달라(내 위시 대 이 토너먼트) 호출자가 먼저 끝낸다.
@Component
class ItemRegistrar(
    private val accessPolicy: DomainAccessPolicy,
    private val itemQuotaGuard: ItemQuotaGuard,
) {
    // quotaOwner 는 요청자가 아니라 몫의 주인이다 - 토너먼트는 참여자가 넣어도 오너 몫에서 깎인다(ItemQuotaGuard 참고).
    fun accept(
        link: ProductLink,
        quotaOwner: UUID,
    ) {
        accessPolicy.verifyRegistrable(link)
        itemQuotaGuard.consume(quotaOwner, 1, ItemErrorCode.QUOTA_EXCEEDED)
    }
}
