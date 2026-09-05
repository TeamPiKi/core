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
    // 이 아이템을 위시에 담은 유저들. 같은 아이템을 여러 유저가 담을 수 있어 DISTINCT. (알림 수신자 역조회)
    // item_id 는 snapshot 단일 출처라 wish→snapshot theta join 으로 itemId 에 도달한다(FK·연관관계 없음).
    @Query(
        "SELECT DISTINCT w.userId FROM Wish w " +
            "WHERE w.snapshotId = :snapshotId AND w.deletedAt IS NULL",
    )
    fun findUserIdsBySnapshotId(
        @Param("snapshotId") snapshotId: Long,
    ): List<UUID>

    // 이 버전을 담은 위시의 (주인, 위시 id). 파싱 알림의 수신자별 wishId 딥링크 역조회(#933) — 각 위시 주인에게
    // 자기 위시 상세로 가는 wishId 를 실어주려면 userId 와 함께 위시 id 가 필요하다.
    @Query(
        "SELECT w.userId AS userId, w.id AS wishId FROM Wish w " +
            "WHERE w.snapshotId = :snapshotId AND w.deletedAt IS NULL",
    )
    fun findOwnerWishIdsBySnapshotId(
        @Param("snapshotId") snapshotId: Long,
    ): List<WishOwnerView>

    // 이 버전으로 **새로고침한** 위시의 (주인, 위시 id)(#1036). 위시는 생성 시 이미 있는 버전을 가리키므로
    // (등록·공유 합류 모두 snapshot 저장 뒤 wish 저장) 위시가 버전보다 먼저 만들어졌다는 것은 곧 생성 후 포인터가
    // 그 버전으로 스왑됐다는 뜻이고, 파싱 대상 버전으로 스왑하는 경로는 새로고침(진행 중 합류 포함)뿐이다.
    // 등록/새로고침을 구분하는 컬럼을 두지 않고 이 시각 비교로 판정한다. created_at 은 DATETIME(6).
    @Query(
        "SELECT w.userId AS userId, w.id AS wishId FROM Wish w, ItemSnapshot s " +
            "WHERE w.snapshotId = s.id AND s.id = :snapshotId AND w.createdAt < s.createdAt AND w.deletedAt IS NULL",
    )
    fun findOwnerWishIdsRefreshedToSnapshot(
        @Param("snapshotId") snapshotId: Long,
    ): List<WishOwnerView>

    // 위와 같은 (주인, 위시 id) 이되 **버전이 아니라 아이템** 으로 찾고, 지정한 상태의 버전을 가리키는 위시만 고른다(#1028).
    // 해소 통지의 수신자는 "방금 성공한 그 버전" 이 아니라 **다른 미완성 버전**(FAILED·INCOMPLETE)에 멈춰 있던 사람이라,
    // 버전 역조회로는 닿지 않는다. 어떤 상태를 미완성으로 볼지는 알림 쪽(ItemParsingRecipientResolver)이 정한다.
    @Query(
        "SELECT w.userId AS userId, w.id AS wishId FROM Wish w, ItemSnapshot s " +
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
