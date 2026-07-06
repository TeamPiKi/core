package com.depromeet.piki.image.repository

import com.depromeet.piki.image.domain.PendingUpload
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class PendingUploadRepositoryImpl(
    private val jpaRepository: PendingUploadJpaRepository,
) : PendingUploadRepository {
    override fun saveAll(uploads: List<PendingUpload>): List<PendingUpload> = jpaRepository.saveAll(uploads)

    override fun findAllByImageKeysForUpdate(imageKeys: List<String>): List<PendingUpload> =
        // 빈 IN 절은 무의미한 쿼리라 조회 자체를 건너뛴다.
        imageKeys.takeIf { it.isNotEmpty() }?.let { jpaRepository.findAllByImageKeyInForUpdate(it) } ?: emptyList()

    override fun findLiveForPolling(
        now: LocalDateTime,
        pollableBefore: LocalDateTime,
        batchSize: Int,
    ): List<PendingUpload> = jpaRepository.findLive(now, pollableBefore, PageRequest.of(0, batchSize))

    override fun findExpired(
        now: LocalDateTime,
        batchSize: Int,
    ): List<PendingUpload> = jpaRepository.findExpired(now, PageRequest.of(0, batchSize))

    override fun deleteAll(uploads: List<PendingUpload>) = jpaRepository.deleteAll(uploads)
}
