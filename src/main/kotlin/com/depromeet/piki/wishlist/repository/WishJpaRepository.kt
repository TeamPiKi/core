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
    // 이 버전을 담은 위시의 (주인, 위시 id, 새로고침 여부). 파싱 알림의 수신자·수신자별 wishId 딥링크 역조회(#933)가
    // 한 번 읽어 나눠 쓴다 — 수신자 도출과 라우팅 해석이 같은 행을 두 번 읽지 않게 한 조회로 모았다.
    // refreshed(#1036)는 위시가 버전보다 먼저 만들어졌는가 = 새로고침으로 이 버전에 도달했는가(WishOwnerView 참고).
    // "이 버전을 기다리는 위시" 라 waitingSnapshotId 로 찾고, 시각 비교를 위해 버전 행을 조인한다(FK·연관관계 없음).
    @Query(
        "SELECT w.userId AS userId, w.id AS wishId, " +
            "CASE WHEN w.createdAt < s.createdAt THEN true ELSE false END AS refreshed " +
            "FROM Wish w, ItemSnapshot s " +
            "WHERE w.waitingSnapshotId = s.id AND s.id = :snapshotId AND w.deletedAt IS NULL",
    )
    fun findOwnerWishIdsBySnapshotId(
        @Param("snapshotId") snapshotId: Long,
    ): List<WishOwnerView>

    // 이 상품을 담은 위시 카드 전부(주인, 위시 id, 기다리는 행) — 해소 통지(#1028) 수신자 판정용(#1051). 어느 카드가
    // 이 파싱으로 채워지는지는 알림 쪽이 표시값 규칙(ItemVersions)으로 가른다. 상태로 좁히지 않는다 — 카드의 미완성은
    // 기다리는 행만이 아니라 주인의 다른 행(내 INCOMPLETE)에서도 올 수 있어 SQL 로 정확히 표현되지 않는다.
    @Query(
        "SELECT w.userId AS userId, w.id AS wishId, w.waitingSnapshotId AS waitingSnapshotId FROM Wish w " +
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

    // 출전·메모용 — 한 유저가 이 itemId 들을 담은 wish. 위시가 item 을 직접 참조하므로(#1051) 조인이 없다.
    @Query(
        "SELECT w FROM Wish w WHERE w.itemId IN :itemIds AND w.userId = :userId AND w.deletedAt IS NULL",
    )
    fun findByItemIdInAndUserIdAndDeletedAtIsNull(
        @Param("itemIds") itemIds: Collection<Long>,
        @Param("userId") userId: UUID,
    ): List<Wish>

    // 정체성 병합 추종(#1051) — 진 item 을 가리키던 위시를 이긴 item 으로. 삭제된 위시도 함께 옮긴다(참조 정합).
    // native bulk 라 auditing 을 우회해 updated_at 을 직접 갱신한다(item_snapshots.reparentAll 과 같은 결).
    @Modifying
    @Query(
        value = "UPDATE wishes SET item_id = :toItemId, updated_at = NOW(6) WHERE item_id = :fromItemId",
        nativeQuery = true,
    )
    fun reparentItem(
        @Param("fromItemId") fromItemId: Long,
        @Param("toItemId") toItemId: Long,
    ): Int

    // 탈퇴 cascade — 그 유저의 위시를 영구 하드삭제. 위시는 다른 데이터가 참조하지 않아 즉시 파기 가능. 멱등(없으면 0건).
    @Modifying
    @Query("DELETE FROM Wish w WHERE w.userId = :userId")
    fun hardDeleteAllByUserId(
        @Param("userId") userId: UUID,
    ): Int
}
