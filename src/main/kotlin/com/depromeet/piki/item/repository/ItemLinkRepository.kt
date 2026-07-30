package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ItemLink

interface ItemLinkRepository {
    // 별칭 한 줄을 기록한다. 이미 같은 url_hash 가 있으면 아무것도 하지 않고 false — 동시 등록·재등록이
    // 예외 없이 "한쪽만 성공"으로 수렴하는 것이 별칭 테이블의 계약이라, 예외가 아니라 반환값으로 알린다.
    fun recordIfAbsent(
        url: String,
        urlHash: String,
        itemId: Long,
    ): Boolean

    fun findByUrlHash(urlHash: String): ItemLink?

    fun findByItemId(itemId: Long): List<ItemLink>
}
