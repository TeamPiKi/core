package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.Item

interface ItemRepository {
    fun save(item: Item): Item

    fun saveAll(items: List<Item>): List<Item>

    fun findByIds(ids: List<Long>): List<Item>

    fun findById(id: Long): Item?

    // canonical 이 아직 없을 때만 확정한다(조건부 claim). true=이번 호출이 확정 / false=이미 확정돼 있음.
    // 다른 item 이 같은 hash 를 소유한 경우는 unique 위반 예외로 드러난다 — 호출부가 병합 후보로 관측한다.
    fun claimCanonicalIfAbsent(
        id: Long,
        canonicalUrl: String,
        canonicalHash: String,
    ): Boolean

    fun findByCanonicalHash(canonicalHash: String): Item?
}
