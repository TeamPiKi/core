package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ParseRequest
import java.time.LocalDateTime

// 파싱 작업 큐의 저장소(#1073). 2단계부터 집기·회수·마감·소유권이 전부 이 테이블을 본다.
interface ParseRequestRepository {
    fun save(request: ParseRequest): ParseRequest

    fun findBySnapshotId(snapshotId: Long): ParseRequest?

    // 전이의 fence 검사와 쓰기를 원자화하는 비관적 락 단건 조회. 삭제된 행 제외.
    fun findByIdForUpdate(id: Long): ParseRequest?

    // 디스패처가 집을 PENDING 요청을 FIFO 로 batchSize 개, FOR UPDATE SKIP LOCKED.
    fun findDuePending(batchSize: Int): List<ParseRequest>

    // 박동이 끊긴 PROCESSING 요청 — heartbeatAt 이 threshold 이전인 것 batchSize 개, FOR UPDATE SKIP LOCKED.
    fun findStaleProcessing(
        threshold: LocalDateTime,
        batchSize: Int,
    ): List<ParseRequest>

    // 마감 초과 요청 — createdAt 이 threshold 이전인 비-터미널 요청 batchSize 개, FOR UPDATE SKIP LOCKED.
    fun findOverdue(
        threshold: LocalDateTime,
        batchSize: Int,
    ): List<ParseRequest>

    // 소유권 획득 — 여전히 expectedAttempt 의 PROCESSING 이면 attempt 를 +1 하고 1을, 아니면 0을 반환. 시도 소모는 여기서만.
    fun acquireOwnership(
        id: Long,
        expectedAttempt: Int,
        now: LocalDateTime,
    ): Int

    // 박동 — 여전히 attempt 의 PROCESSING 이면 heartbeatAt 을 밀고 1을, 아니면 0을 반환.
    fun renewOwnership(
        id: Long,
        attempt: Int,
        now: LocalDateTime,
    ): Int

    // 병합(#825): 진 item 의 요청을 이긴 item 으로. 이동한 행 수 반환.
    fun reparentAll(
        fromItemId: Long,
        toItemId: Long,
        now: LocalDateTime,
    ): Int
}
