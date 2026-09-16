package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ParseRequest

interface ParseRequestRepository {
    fun save(request: ParseRequest): ParseRequest

    fun findBySnapshotId(snapshotId: Long): ParseRequest?
}
