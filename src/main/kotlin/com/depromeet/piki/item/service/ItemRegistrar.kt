package com.depromeet.piki.item.service

import com.depromeet.piki.common.ratelimit.ItemQuota
import com.depromeet.piki.common.ratelimit.ItemQuotaGuard
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.routing.DomainAccessPolicy
import org.springframework.stereotype.Component

// 링크로 아이템을 받아들이는 관문. 위시·토너먼트가 각자 베껴 쓰던 등록 서두를 한 자리로 모은다.
//
// 순서가 이 클래스의 존재 이유다. 형식·정책 위반과 중복은 차감 앞에서 걸러야 한다 — 뒤로 가면
// 등록되지도 않을 요청이 사용자 몫을 깎는다(#973). 두 호출자가 이 순서를 각자 외우던 동안
// 자격 검사 위치가 이미 서로 어긋나 있었다.
//
// 중복 판정만 콜백으로 받는다. 기준이 도메인마다 달라서다 — 위시는 내가 담은 것, 토너먼트는 이 토너먼트에 담긴 것.
@Component
class ItemRegistrar(
    private val accessPolicy: DomainAccessPolicy,
    private val itemQuotaGuard: ItemQuotaGuard,
) {
    fun accept(
        rawUrl: String,
        quota: ItemQuota,
        rejectIfDuplicate: (ProductLink) -> Unit,
    ): ProductLink {
        val link = ProductLink.parse(rawUrl)
        accessPolicy.verifyRegistrable(link)
        rejectIfDuplicate(link)
        itemQuotaGuard.consume(quota.owner, 1, quota.errorCode)
        return link
    }
}
