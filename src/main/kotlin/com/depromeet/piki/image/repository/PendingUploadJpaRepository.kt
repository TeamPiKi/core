package com.depromeet.piki.image.repository

import com.depromeet.piki.image.domain.PendingUpload
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface PendingUploadJpaRepository : JpaRepository<PendingUpload, Long> {
    // claim 직전 FOR UPDATE — confirm 과 폴링이 같은 key 를 다투면 락으로 직렬화되고, 삭제에 성공한 한쪽만 등록한다(멱등).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PendingUpload p where p.imageKey in :imageKeys")
    fun findAllByImageKeyInForUpdate(
        @Param("imageKeys") imageKeys: List<String>,
    ): List<PendingUpload>

    @Query(
        "select p from PendingUpload p where p.expiresAt > :now and p.nextCheckAt <= :now " +
            "order by p.createdAt asc, p.id asc",
    )
    fun findDueForCheck(
        @Param("now") now: LocalDateTime,
        pageable: Pageable,
    ): List<PendingUpload>

    // 만료 정리 대상 — 발급 후 업로드되지 않아 expiresAt 이 지난 매핑.
    @Query("select p from PendingUpload p where p.expiresAt <= :now order by p.expiresAt asc, p.id asc")
    fun findExpired(
        @Param("now") now: LocalDateTime,
        pageable: Pageable,
    ): List<PendingUpload>
}
