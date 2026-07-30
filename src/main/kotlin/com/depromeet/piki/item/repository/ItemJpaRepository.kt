package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.Item
import org.springframework.data.jpa.repository.JpaRepository
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
}
