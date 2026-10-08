package com.depromeet.piki.wishlist.repository

import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.domain.WishCursor
import java.util.UUID

interface WishRepository {
    fun save(wish: Wish): Wish

    // 탈퇴 cascade — 그 유저의 위시를 일괄 하드삭제하고 영향 건수를 돌려준다. 멱등.
    fun hardDeleteAllByUserId(userId: UUID): Int

    fun countByItemIdsAndUserId(
        itemIds: List<Long>,
        userId: UUID,
    ): Long

    // id desc 정렬로 최대 limit 건. cursor 가 있으면 그 id 미만만(다음 페이지). 삭제된 행은 제외.
    fun findPage(
        userId: UUID,
        cursor: WishCursor?,
        limit: Int,
    ): List<Wish>

    // 삭제되지 않은 단건 조회. 없으면 null.
    fun findById(id: Long): Wish?

    // 새로고침·수기 수정·메모를 직렬화한다. 삭제된 행은 제외(없으면 null).
    fun findByIdForUpdate(id: Long): Wish?

    // 삭제되지 않은 위시들을 id 목록으로 조회. 존재하는 것만 반환하므로(없는 id 는 빠진다)
    // 호출부가 반환 개수로 누락 여부를 판단한다.
    fun findAllByIds(ids: List<Long>): List<Wish>

    // 한 유저의 위시를 itemId 목록으로 조회. itemId 는 유저 내에서 사실상 1:1
    fun findByItemIdsAndUserId(
        itemIds: List<Long>,
        userId: UUID,
    ): List<Wish>

    fun findCardsByItemId(itemId: Long): List<WishCardView>

    fun findByIdIncludingDeletedForUpdate(id: Long): Wish?

    fun findIdByUserIdAndUrlWithoutTracking(
        userId: UUID,
        url: String,
    ): Long?
}
