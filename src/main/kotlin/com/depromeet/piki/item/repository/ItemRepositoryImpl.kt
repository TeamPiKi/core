package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.CanonicalInput
import org.springframework.stereotype.Repository

@Repository
class ItemRepositoryImpl(
    private val itemJpaRepository: ItemJpaRepository,
) : ItemRepository {
    override fun save(item: Item): Item = itemJpaRepository.save(item)

    override fun findByIds(ids: List<Long>): List<Item> = itemJpaRepository.findAllById(ids)

    override fun findById(id: Long): Item? = itemJpaRepository.findById(id).orElse(null)

    override fun findByCanonical(canonical: CanonicalInput): Item? = itemJpaRepository.findByCanonicalUrlAndDeletedAtIsNull(canonical)

    override fun findByIdForUpdate(id: Long): Item? = itemJpaRepository.findByIdForUpdate(id)

    override fun findByIdsForUpdate(ids: Collection<Long>): List<Item> =
        if (ids.isEmpty()) emptyList() else itemJpaRepository.findByIdsForUpdate(ids)
}
