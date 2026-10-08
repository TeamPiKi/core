package com.depromeet.piki.wishlist.repository

import com.depromeet.piki.wishlist.domain.Wish
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface WishJpaRepository : JpaRepository<Wish, Long> {
    // 해소 통지(#1028) 후보. 어느 카드가 채워지는지는 알림 쪽이 표시값 규칙(ItemVersions)으로 가른다
    @Query(
        "SELECT w.userId AS userId, w.id AS wishId FROM Wish w " +
            "WHERE w.itemId = :itemId AND w.deletedAt IS NULL ORDER BY w.id ASC",
    )
    fun findCardsByItemId(
        @Param("itemId") itemId: Long,
    ): List<WishCardView>

    // 출전 소유 체크용 — 위시가 item 을 직접 참조하므로(#1051) 조인 없이 센다.
    @Query(
        "SELECT COUNT(DISTINCT w.id) FROM Wish w " +
            "WHERE w.itemId IN :itemIds AND w.userId = :userId AND w.deletedAt IS NULL",
    )
    fun countByItemIdInAndUserIdAndDeletedAtIsNull(
        @Param("itemIds") itemIds: Collection<Long>,
        @Param("userId") userId: UUID,
    ): Long

    fun findByUserIdAndDeletedAtIsNullOrderByIdDesc(
        userId: UUID,
        limit: Limit,
    ): List<Wish>

    fun findByUserIdAndIdLessThanAndDeletedAtIsNullOrderByIdDesc(
        userId: UUID,
        id: Long,
        limit: Limit,
    ): List<Wish>

    fun findByIdAndDeletedAtIsNull(id: Long): Wish?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wish w WHERE w.id = :id AND w.deletedAt IS NULL")
    fun findByIdForUpdate(
        @Param("id") id: Long,
    ): Wish?

    fun findByIdInAndDeletedAtIsNull(ids: Collection<Long>): List<Wish>

    // 파싱 결과는 지워진 위시에도 기록한다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wish w WHERE w.id = :id")
    fun findByIdIncludingDeletedForUpdate(
        @Param("id") id: Long,
    ): Wish?

    // 같은 URL 로 파싱을 요청한 적 있는 위시. 한 사용자의 위시로 먼저 좁혀져 URL 인덱스가 필요 없다
    @Query(
        "SELECT w.id FROM Wish w, ItemParseOutbox o " +
            "WHERE o.requesterType = com.depromeet.piki.item.domain.ParseRequesterType.WISH AND o.requesterId = w.id " +
            "AND w.userId = :userId AND w.deletedAt IS NULL AND o.urlWithoutTracking = :url",
    )
    fun findIdsByUserIdAndUrlWithoutTracking(
        @Param("userId") userId: UUID,
        @Param("url") url: String,
    ): List<Long>

    // 출전·메모용 — 한 유저가 이 itemId 들을 담은 wish. 위시가 item 을 직접 참조하므로(#1051) 조인이 없다.
    @Query(
        "SELECT w FROM Wish w WHERE w.itemId IN :itemIds AND w.userId = :userId AND w.deletedAt IS NULL",
    )
    fun findByItemIdInAndUserIdAndDeletedAtIsNull(
        @Param("itemIds") itemIds: Collection<Long>,
        @Param("userId") userId: UUID,
    ): List<Wish>

    // 탈퇴 cascade — 그 유저의 위시를 영구 하드삭제. 위시는 다른 데이터가 참조하지 않아 즉시 파기 가능. 멱등(없으면 0건).
    @Modifying
    @Query("DELETE FROM Wish w WHERE w.userId = :userId")
    fun hardDeleteAllByUserId(
        @Param("userId") userId: UUID,
    ): Int
}
