package com.depromeet.piki.wishlist.repository

import com.depromeet.piki.item.domain.ItemStatus
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
    // 이 버전을 담은 위시의 (주인, 위시 id, 새로고침 여부). 파싱 알림의 수신자·수신자별 wishId 딥링크 역조회(#933)가
    // 한 번 읽어 나눠 쓴다 — 수신자 도출과 라우팅 해석이 같은 행을 두 번 읽지 않게 한 조회로 모았다.
    // refreshed(#1036)는 위시가 버전보다 먼저 만들어졌는가 = 새로고침으로 이 버전에 도달했는가(WishOwnerView 참고).
    // item_id 는 snapshot 단일 출처라 wish→snapshot theta join 으로 도달한다(FK·연관관계 없음).
    @Query(
        "SELECT w.userId AS userId, w.id AS wishId, " +
            "CASE WHEN w.createdAt < s.createdAt THEN true ELSE false END AS refreshed " +
            "FROM Wish w, ItemSnapshot s " +
            "WHERE w.snapshotId = s.id AND s.id = :snapshotId AND w.deletedAt IS NULL",
    )
    fun findOwnerWishIdsBySnapshotId(
        @Param("snapshotId") snapshotId: Long,
    ): List<WishOwnerView>

    // 위와 같은 (주인, 위시 id) 이되 **버전이 아니라 아이템** 으로 찾고, 지정한 상태의 버전을 가리키는 위시만 고른다(#1028).
    // 해소 통지의 수신자는 "방금 성공한 그 버전" 이 아니라 **다른 미완성 버전**(FAILED·INCOMPLETE)에 멈춰 있던 사람이라,
    // 버전 역조회로는 닿지 않는다. 어떤 상태를 미완성으로 볼지는 알림 쪽(ItemParsingRecipientResolver)이 정한다.
    // refreshed 는 뷰 계약을 맞추기 위해 같은 식으로 채운다(해소 통지는 이 값을 쓰지 않는다).
    @Query(
        "SELECT w.userId AS userId, w.id AS wishId, " +
            "CASE WHEN w.createdAt < s.createdAt THEN true ELSE false END AS refreshed " +
            "FROM Wish w, ItemSnapshot s " +
            "WHERE w.snapshotId = s.id AND s.itemId = :itemId AND s.status IN :statuses AND w.deletedAt IS NULL",
    )
    fun findOwnerWishIdsByItemIdAndStatuses(
        @Param("itemId") itemId: Long,
        @Param("statuses") statuses: Collection<ItemStatus>,
    ): List<WishOwnerView>

    fun countByIdInAndUserId(
        ids: Collection<Long>,
        userId: UUID,
    ): Long

    // wish→snapshot theta join 으로 itemId 에 도달(파생 이름은 유지하되 @Query 가 우선한다). 알림·출전 소유 체크용.
    @Query(
        "SELECT COUNT(DISTINCT w.id) FROM Wish w, ItemSnapshot s " +
            "WHERE w.snapshotId = s.id AND s.itemId IN :itemIds AND w.userId = :userId AND w.deletedAt IS NULL",
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

    // 출전용 — 한 유저가 이 itemId 들을 담은 wish 를 snapshot theta join 으로 조회한다(고정할 활성 snapshotId 를 읽는다).
    @Query(
        "SELECT w FROM Wish w, ItemSnapshot s " +
            "WHERE w.snapshotId = s.id AND s.itemId IN :itemIds AND w.userId = :userId AND w.deletedAt IS NULL",
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
