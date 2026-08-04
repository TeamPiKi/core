package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ItemSnapshot
import java.time.LocalDateTime

interface ItemSnapshotRepository {
    fun save(snapshot: ItemSnapshot): ItemSnapshot

    fun saveAll(snapshots: List<ItemSnapshot>): List<ItemSnapshot>

    // 한 item 의 최신 snapshot. 2단계는 item 당 snapshot 1행이라 사실상 그 행이며,
    // 상태 전이(markReady/markFailed/recover) 대상을 찾는 데 쓴다. 5단계 갱신에서 여러 버전이 쌓이면 "최신 버전" 의미가 된다.
    fun findLatestByItemId(itemId: Long): ItemSnapshot?

    // 공유 등록(#825): 진행 중 합류 판정.
    fun findLatestInProgressByItemId(itemId: Long): ItemSnapshot?

    // 공유 등록(#825): 신선도 재사용 판정 — 마지막 기계 READY.
    fun findLatestMachineReadyByItemId(itemId: Long): ItemSnapshot?

    // 카드 표시값 파생(#857): item 별 마지막 기계 READY 배치 조회. 없는 item 은 결과에서 빠진다.
    fun findLatestMachineReadyByItemIds(itemIds: Collection<Long>): List<ItemSnapshot>

    // 병합(#825): 진 item 의 모든 버전을 이긴 item 으로 재부모화. 이동한 행 수 반환.
    fun reparentAll(
        fromItemId: Long,
        toItemId: Long,
    ): Int

    // 가격 이력 조회 — 한 item 의 기계(SERVER/SERVER_LLM) READY 버전을 최신순(id desc)으로 limit 개까지.
    // 가격이 없는 PENDING/PROCESSING/FAILED 와, 시계열의 관측치가 아닌 수기(MANUAL)·출처 미상(null)은 빠진다.
    // limit 을 받는 이유: item 을 여러 사용자가 공유해 새로고침이 누적되므로 상한이 없으면 응답이 계속 자란다.
    fun findMachineReadyHistoryByItemId(
        itemId: Long,
        limit: Int,
    ): List<ItemSnapshot>

    // 3단계(참조 이전): wish/tournament_item 이 가리키는 고정 snapshot 을 id 로 끌어온다.
    // 단건 조회(위시 단건·토너먼트 아이템 단건)용. 삭제된 행은 제외, 없으면 null.
    fun findById(id: Long): ItemSnapshot?

    // 전이의 fence 검사→쓰기를 원자화하는 비관적 락(FOR UPDATE) 단건 조회. 삭제된 행 제외, 없으면 null.
    // 소유권 획득(acquire)·박동(renew)과 같은 행 락에서 직렬화된다(자세한 이유는 ItemSnapshotJpaRepository.findByIdForUpdate).
    fun findByIdForUpdate(id: Long): ItemSnapshot?

    // 여러 snapshot 을 id 목록으로 한 번에 조회(목록·결과 조회의 메모리 조인용). 존재하는 것만 반환.
    fun findByIds(ids: List<Long>): List<ItemSnapshot>

    // 디스패처가 집을 PENDING snapshot 을 FIFO 로 batchSize 개, FOR UPDATE 로 잠가 가져온다(작업 큐 claim 대상).
    fun findDuePending(batchSize: Int): List<ItemSnapshot>

    // recover 가 집을 stale PROCESSING snapshot — claim 시각(updated_at)이 threshold 이전인 것 batchSize 개, FOR UPDATE.
    fun findStaleProcessing(
        threshold: LocalDateTime,
        batchSize: Int,
    ): List<ItemSnapshot>

    // 마감 초과 행 — created_at 이 threshold 이전인 비-터미널(PENDING·PROCESSING) snapshot batchSize 개, FOR UPDATE.
    // stale(updated_at)과 다른 시계다 (자세한 계약은 ItemSnapshotJpaRepository.findOverdueForUpdate).
    fun findOverdue(
        threshold: LocalDateTime,
        batchSize: Int,
    ): List<ItemSnapshot>

    // 소유권 획득 — snapshotId 가 여전히 expectedAttempt 의 PROCESSING 이면 attempt 를 +1 하고 1을, 아니면 0을 반환한다.
    // 시도 소모는 오직 여기서 일어난다 (자세한 계약은 ItemSnapshotJpaRepository.acquireOwnership).
    fun acquireOwnership(
        snapshotId: Long,
        expectedAttempt: Int,
        now: LocalDateTime,
    ): Int

    // 소유권 갱신(박동) — snapshotId 가 여전히 attempt 의 PROCESSING 이면 updated_at 을 now 로 밀고 1을, 아니면 0을 반환한다.
    // 산 워커의 stale 오판 방지 + 소유권 fencing (자세한 계약은 ItemSnapshotJpaRepository.renewOwnership).
    fun renewOwnership(
        snapshotId: Long,
        attempt: Int,
        now: LocalDateTime,
    ): Int
}
