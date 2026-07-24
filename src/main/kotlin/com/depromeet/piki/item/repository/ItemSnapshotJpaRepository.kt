package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

// FOR UPDATE 에 SKIP LOCKED 를 더하는 힌트 값 (Hibernate LockOptions.SKIP_LOCKED = -2).
// claim 계열 locking read 가 잠긴 레코드를 만나면 "대기" 대신 "건너뛰기" 한다. 교착의 재료는 상호 대기라,
// 이 스캔이 절대 기다리지 않으면 스캔이 끼는 사이클은 성립 자체가 불가능하다.
// 실측(InnoDB deadlock 리포트 확보): 동시에 돈 claim 둘이 같은 snapshot 행을 보조 인덱스(스캔의 next-key)와
// PRIMARY(상태 전이 UPDATE 의 인덱스 유지보수)에서 서로 반대 순서로 잠가 교착했다. 이 계열의 victim 이
// 사용자 요청 트랜잭션 쪽으로 떨어지면 간헐 500 이 된다. 건너뛴 행은 다음 폴링 주기가 집는다.
// 부수 발견: 옵티마이저가 이 SELECT 를 PRIMARY 풀스캔으로 실행하면 FOR UPDATE 가 전 행 + supremum 에
// next-key 락을 깔아 충돌 표면이 상태 필터와 무관하게 커진다 — SKIP LOCKED 는 그 경우에도 대기를 제거한다.
private const val SKIP_LOCKED = "-2"

interface ItemSnapshotJpaRepository : JpaRepository<ItemSnapshot, Long> {
    // 한 item 의 살아있는(soft-delete 안 된) 최신 snapshot 1개 — id 역순 첫 행.
    fun findFirstByItemIdAndDeletedAtIsNullOrderByIdDesc(itemId: Long): ItemSnapshot?

    // 한 item 의 특정 상태 snapshot 전체를 id 역순(최신 버전 먼저)으로 — 가격 히스토리(READY 버전 이력) 조회용.
    // idx_item_snapshots_item_id 로 커버되고, id 정렬은 PK 라 secondary index 리프에 포함돼 추가 인덱스가 필요 없다.
    fun findByItemIdAndStatusAndDeletedAtIsNullOrderByIdDesc(
        itemId: Long,
        status: ItemStatus,
    ): List<ItemSnapshot>

    // 살아있는 단건 조회. JpaRepository.findById(Optional) 와 충돌하지 않도록 deletedAt 조건을 붙여 이름을 구분한다.
    fun findByIdAndDeletedAtIsNull(id: Long): ItemSnapshot?

    // 살아있는 행만 id 목록으로 일괄 조회.
    fun findByIdInAndDeletedAtIsNull(ids: Collection<Long>): List<ItemSnapshot>

    // 디스패처가 집을 작업(PENDING) snapshot 을 FIFO(created_at)로 limit 개, FOR UPDATE SKIP LOCKED 로 잠가 가져온다.
    // 락으로 같은 행을 두 디스패처가 동시에 claim 하는 것을 막고(멀티 인스턴스 대비), SKIP LOCKED 로 잠긴 entry
    // (미커밋 insert 포함)는 대기 없이 건너뛴다 — 교착 제거(위 SKIP_LOCKED 주석). limit 은 Pageable 로 주입.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = SKIP_LOCKED))
    @Query("select s from ItemSnapshot s where s.status = :status and s.deletedAt is null order by s.createdAt asc, s.id asc")
    fun findByStatusForUpdate(
        @Param("status") status: ItemStatus,
        pageable: Pageable,
    ): List<ItemSnapshot>

    // recover 가 집을 stale 작업 — updated_at(claim 시각)이 threshold 이전인 PROCESSING snapshot 을 limit 개,
    // FOR UPDATE SKIP LOCKED. created_at 이 아니라 updated_at 기준이라, PENDING 으로 오래 갇혔다 방금 claim 된 행은
    // stale 로 오판하지 않는다. SKIP LOCKED 는 claim 과 같은 교착 제거 목적 — 워커의 상태 전이(READY/FAILED) 트랜잭션과
    // recover 스캔이 얽히는 같은 구조의 사이클을 예방한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = SKIP_LOCKED))
    @Query(
        "select s from ItemSnapshot s where s.status = :status and s.updatedAt < :threshold and s.deletedAt is null " +
            "order by s.updatedAt asc, s.id asc",
    )
    fun findStaleByStatusForUpdate(
        @Param("status") status: ItemStatus,
        @Param("threshold") threshold: LocalDateTime,
        pageable: Pageable,
    ): List<ItemSnapshot>
}
