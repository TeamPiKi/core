package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.Item
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ItemJpaRepository : JpaRepository<Item, Long> {
    // 조건부 canonical claim — "canonical 이 아직 없을 때만" 확정한다. 같은 item 을 두 트랜잭션이 동시에
    // 확정하는 경합을 인메모리 검사(Item.claimCanonical) 대신 DB 문장이 직렬화한다: 이긴 쪽만 1행,
    // 진 쪽은 0행을 받아 재조회로 판정한다. 다른 item 이 같은 hash 를 이미 소유한 경우는 unique
    // (uq_items_canonical_hash) 위반 예외로 갈라진다 — 호출부(ItemIdentityRecorder)가 병합 후보로 관측한다.
    // native 인 이유: @Modifying bulk update 는 JPA auditing 을 우회하므로 updated_at 을 직접 채운다.
    @Modifying
    @Query(
        value = """
            UPDATE items
            SET canonical_url = :url, canonical_hash = :hash, updated_at = NOW(6)
            WHERE id = :id AND canonical_hash IS NULL AND deleted_at IS NULL
        """,
        nativeQuery = true,
    )
    fun claimCanonicalIfAbsent(
        @Param("id") id: Long,
        @Param("url") url: String,
        @Param("hash") hash: String,
    ): Int

    fun findByCanonicalHashAndDeletedAtIsNull(canonicalHash: String): Item?

    fun findBySourceImageKeyInAndDeletedAtIsNull(keys: Collection<String>): List<Item>

    // 공유 등록(#825)의 attach 판정(합류/재사용/새 작업)을 item 행 락으로 직렬화한다 — 동시 등록 두 건이 각자
    // 새 PENDING 을 만들어 같은 item 을 중복 파싱하는 낭비를 막는다(#826).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Item i where i.id = :id and i.deletedAt is null")
    fun findByIdForUpdate(
        @Param("id") id: Long,
    ): Item?

    // 병합(#825) — 빈 껍데기가 된 진(임시) item 을 soft delete 한다. native 라 updated_at 을 직접 갱신.
    @Modifying
    @Query(
        value = "UPDATE items SET deleted_at = NOW(6), updated_at = NOW(6) WHERE id = :id AND deleted_at IS NULL",
        nativeQuery = true,
    )
    fun softDeleteById(
        @Param("id") id: Long,
    ): Int
}
