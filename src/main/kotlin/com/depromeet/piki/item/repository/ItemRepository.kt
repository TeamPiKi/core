package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.CanonicalInput
import com.depromeet.piki.item.domain.Item

interface ItemRepository {
    fun save(item: Item): Item

    fun findByIds(ids: List<Long>): List<Item>

    fun findByIdsForShare(ids: Collection<Long>): List<Item>

    fun findById(id: Long): Item?

    fun findByCanonical(canonical: CanonicalInput): Item?

    fun findByIdForUpdate(id: Long): Item?

    // id 순서로 잠가 여러 아이템을 잡는 경로끼리 교착하지 않게 한다
    fun findByIdsForUpdate(ids: Collection<Long>): List<Item>
}
