package com.depromeet.piki.wishlist.repository

import com.depromeet.piki.wishlist.domain.WishMemo
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class WishMemoRepositoryImpl(
    private val wishMemoJpaRepository: WishMemoJpaRepository,
) : WishMemoRepository {
    override fun save(memo: WishMemo): WishMemo = wishMemoJpaRepository.save(memo)

    override fun saveAll(memos: List<WishMemo>): List<WishMemo> = wishMemoJpaRepository.saveAll(memos)

    override fun findCurrentByWishId(wishId: Long): WishMemo? = wishMemoJpaRepository.findFirstByWishIdOrderByWrittenAtDescIdDesc(wishId)

    override fun findAllByWishId(wishId: Long): List<WishMemo> = wishMemoJpaRepository.findAllByWishId(wishId)

    override fun hardDeleteAllByUserId(userId: UUID): Int = wishMemoJpaRepository.hardDeleteAllByUserId(userId)
}
