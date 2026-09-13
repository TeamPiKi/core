package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ParseRequest
import org.springframework.stereotype.Repository

@Repository
class ParseRequestRepositoryImpl(
    private val parseRequestJpaRepository: ParseRequestJpaRepository,
) : ParseRequestRepository {
    override fun save(request: ParseRequest): ParseRequest = parseRequestJpaRepository.save(request)

    override fun findBySnapshotId(snapshotId: Long): ParseRequest? =
        parseRequestJpaRepository.findByResultSnapshotIdAndDeletedAtIsNull(snapshotId)
}
