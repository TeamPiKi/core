package com.depromeet.piki.image.repository

import com.depromeet.piki.image.domain.PendingUpload
import java.time.LocalDateTime

interface PendingUploadRepository {
    fun saveAll(uploads: List<PendingUpload>): List<PendingUpload>

    // 주어진 key 들의 대기 매핑을 FOR UPDATE 로 잠가 조회한다(claim 직전 락). 존재하는 것만 반환.
    // 호출부가 같은 트랜잭션에서 deleteAll 로 claim 을 확정한다 — confirm 과 폴링이 같은 key 를 다퉈도 한쪽만 이긴다.
    fun findAllByImageKeysForUpdate(imageKeys: List<String>): List<PendingUpload>

    // 폴링이 집을 대기 매핑 — 아직 안 만료됐고 발급 grace(pollableBefore)가 지난 것 batchSize 개(HEAD 확인 대상, FIFO).
    // grace 로 confirm(빠른 경로)에 우선권을 줘 confirm·폴링이 같은 key 를 다투는 레이스를 줄인다.
    fun findLiveForPolling(
        now: LocalDateTime,
        pollableBefore: LocalDateTime,
        batchSize: Int,
    ): List<PendingUpload>

    // 만료된(발급 후 업로드되지 않은) 매핑 batchSize 개 — 폴링이 정리 대상으로 집는다.
    fun findExpired(
        now: LocalDateTime,
        batchSize: Int,
    ): List<PendingUpload>

    fun deleteAll(uploads: List<PendingUpload>)
}
