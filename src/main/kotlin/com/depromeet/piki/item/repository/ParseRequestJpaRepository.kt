package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ParseRequest
import org.springframework.data.jpa.repository.JpaRepository

interface ParseRequestJpaRepository : JpaRepository<ParseRequest, Long> {
    fun findByResultSnapshotIdAndDeletedAtIsNull(resultSnapshotId: Long): ParseRequest?
}
