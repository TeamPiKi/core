package com.depromeet.piki.wishlist.repository

import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.domain.WishCursor
import java.util.UUID

interface WishRepository {
    fun save(wish: Wish): Wish

    // 탈퇴 cascade — 그 유저의 위시를 일괄 하드삭제하고 영향 건수를 돌려준다. 멱등.
    fun hardDeleteAllByUserId(userId: UUID): Int

    fun countByIdsAndUserId(
        ids: List<Long>,
        userId: UUID,
    ): Long

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

    // 수동 새로고침용 — 행 락으로 동시 새로고침을 직렬화한다. 삭제된 행은 제외(없으면 null).
    // item↔wish 가 등록당 1:1 이라 wish 락이 곧 그 item 의 갱신 직렬화 효과를 낸다(한 item 에 진행 중 snapshot 최대 1개 보장).
    fun findByIdForUpdate(id: Long): Wish?

    // 삭제되지 않은 위시들을 id 목록으로 조회. 존재하는 것만 반환하므로(없는 id 는 빠진다)
    // 호출부가 반환 개수로 누락 여부를 판단한다.
    fun findAllByIds(ids: List<Long>): List<Wish>

    // 한 유저의 위시를 itemId 목록으로 조회. 토너먼트로 출전시킬 때 각 위시의 활성 snapshotId 를
    // 읽어 tournament_item 에 고정하는 데 쓴다. itemId 는 등록당 1건이라 유저 내에서 사실상 1:1.
    fun findByItemIdsAndUserId(
        itemIds: List<Long>,
        userId: UUID,
    ): List<Wish>

    // 이 아이템을 위시에 담은 유저들 (알림 수신자 역조회). 같은 아이템을 여러 유저가 담을 수 있다.
    fun findUserIdsBySnapshotId(snapshotId: Long): List<UUID>

    fun findOwnerWishIdsBySnapshotId(snapshotId: Long): List<WishOwnerView>
}
