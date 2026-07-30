package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.Item
import org.springframework.stereotype.Repository

@Repository
class ItemRepositoryImpl(
    private val itemJpaRepository: ItemJpaRepository,
) : ItemRepository {
    override fun save(item: Item): Item = itemJpaRepository.save(item)

    override fun saveAll(items: List<Item>): List<Item> = itemJpaRepository.saveAll(items)

    override fun findByIds(ids: List<Long>): List<Item> = itemJpaRepository.findAllById(ids)

    override fun findById(id: Long): Item? = itemJpaRepository.findById(id).orElse(null)

    override fun claimCanonicalIfAbsent(
        id: Long,
        canonicalUrl: String,
        canonicalHash: String,
    ): Boolean = itemJpaRepository.claimCanonicalIfAbsent(id, canonicalUrl, canonicalHash) == 1

    override fun findByCanonicalHash(canonicalHash: String): Item? =
        itemJpaRepository.findByCanonicalHashAndDeletedAtIsNull(canonicalHash)
}
