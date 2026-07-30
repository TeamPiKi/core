package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ItemLink
import org.springframework.stereotype.Repository

@Repository
class ItemLinkRepositoryImpl(
    private val itemLinkJpaRepository: ItemLinkJpaRepository,
) : ItemLinkRepository {
    override fun recordIfAbsent(
        url: String,
        urlHash: String,
        itemId: Long,
    ): Boolean = itemLinkJpaRepository.insertIgnore(url, urlHash, itemId) == 1

    override fun findByUrlHash(urlHash: String): ItemLink? = itemLinkJpaRepository.findByUrlHashAndDeletedAtIsNull(urlHash)

    override fun findByItemId(itemId: Long): List<ItemLink> = itemLinkJpaRepository.findByItemIdAndDeletedAtIsNull(itemId)
}
