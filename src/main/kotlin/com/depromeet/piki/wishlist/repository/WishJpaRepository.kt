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
