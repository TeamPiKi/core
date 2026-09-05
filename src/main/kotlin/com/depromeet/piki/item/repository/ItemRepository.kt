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

    fun findBySourceImageKeys(keys: Collection<String>): List<Item>

    // 공유 등록 attach 판정 직렬화용 행 락 조회(#826).
    fun findByIdForUpdate(id: Long): Item?

    // 병합: 빈 임시 item soft delete.
    fun softDeleteById(id: Long)
}
