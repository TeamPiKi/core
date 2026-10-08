package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.Item
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ItemJpaRepository : JpaRepository<Item, Long> {
    // 버전 생성과 토너먼트 시작을 직렬화한다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Item i where i.id = :id and i.deletedAt is null")
    fun findByIdForUpdate(
        @Param("id") id: Long,
    ): Item?

    fun findByCanonicalUrlAndDeletedAtIsNull(canonicalUrl: String): Item?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Item i where i.id in :ids and i.deletedAt is null order by i.id")
    fun findByIdsForUpdate(
        @Param("ids") ids: Collection<Long>,
    ): List<Item>
}
