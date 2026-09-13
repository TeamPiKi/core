package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ParseRequest
import com.depromeet.piki.item.domain.ParseRequestStatus
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class ParseRequestRepositoryImpl(
    private val parseRequestJpaRepository: ParseRequestJpaRepository,
) : ParseRequestRepository {
    override fun save(request: ParseRequest): ParseRequest = parseRequestJpaRepository.save(request)

    override fun findBySnapshotId(snapshotId: Long): ParseRequest? =
        parseRequestJpaRepository.findByResultSnapshotIdAndDeletedAtIsNull(snapshotId)

    override fun findByIdForUpdate(id: Long): ParseRequest? = parseRequestJpaRepository.findByIdForUpdate(id)

    override fun findDuePending(batchSize: Int): List<ParseRequest> =
        parseRequestJpaRepository.findByStatusForUpdate(ParseRequestStatus.PENDING, PageRequest.of(0, batchSize))

    override fun findStaleProcessing(
        threshold: LocalDateTime,
        batchSize: Int,
    ): List<ParseRequest> =
        parseRequestJpaRepository.findStaleByStatusForUpdate(
            ParseRequestStatus.PROCESSING,
            threshold,
            PageRequest.of(0, batchSize),
        )

    override fun findOverdue(
        threshold: LocalDateTime,
        batchSize: Int,
    ): List<ParseRequest> =
        parseRequestJpaRepository.findOverdueForUpdate(
            listOf(ParseRequestStatus.PENDING, ParseRequestStatus.PROCESSING),
            threshold,
            PageRequest.of(0, batchSize),
        )

    override fun acquireOwnership(
        id: Long,
        expectedAttempt: Int,
        now: LocalDateTime,
    ): Int = parseRequestJpaRepository.acquireOwnership(id, ParseRequestStatus.PROCESSING, expectedAttempt, now)

    override fun renewOwnership(
        id: Long,
        attempt: Int,
        now: LocalDateTime,
    ): Int = parseRequestJpaRepository.renewOwnership(id, ParseRequestStatus.PROCESSING, attempt, now)

    override fun reparentAll(
        fromItemId: Long,
        toItemId: Long,
        now: LocalDateTime,
    ): Int = parseRequestJpaRepository.reparentAll(fromItemId, toItemId, now)
}
