package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime
import java.util.UUID

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

    // 가격 이력 조회 — 한 item 의 출처가 기록된(SERVER/SERVER_LLM/MANUAL) READY 버전을 최신순(id desc)으로
    // pageable 개수만큼. 수기(MANUAL)는 편집자를 가리지 않고 다 담는다 — 같은 상품을 공유하는 다른 사용자가
    // 넣은 값도 그 상품의 가격 기록이며, 응답에서 editedByMe 로 본인 것인지만 구분해 준다(편집자 식별자 자체는
    // 내리지 않는다). 이 상품을 담은 사람들이 실제로 얼마에 봤는지가 시세 판단에 쓰인다.
    //
    // 출처 null(도입 전 행)은 서버 추출인지 사용자 입력인지 소급 판정할 수 없어 제외한다 — 표시값 파생
    // (findLatestMachineReady*)이 쓰는 기준과 같다. 그것만 다르게 두면 "표시값 판정에선 안 세면서 이력엔 넣는"
    // 모순이 된다.
    //
    // idx_item_snapshots_item_id 로 커버되고, id 정렬은 PK 라 secondary index 리프에 포함돼 추가 인덱스가 필요 없다.
    @Query(
        "select s from ItemSnapshot s where s.itemId = :itemId " +
            "and s.status = com.depromeet.piki.item.domain.ItemStatus.READY " +
            "and s.deletedAt is null " +
            "and s.source is not null " +
            "order by s.id desc",
    )
    fun findPriceHistoryByItemId(
        @Param("itemId") itemId: Long,
        pageable: Pageable,
    ): List<ItemSnapshot>

    // 살아있는 단건 조회. JpaRepository.findById(Optional) 와 충돌하지 않도록 deletedAt 조건을 붙여 이름을 구분한다.
    fun findByIdAndDeletedAtIsNull(id: Long): ItemSnapshot?

    // 전이(markReady/markFailed)가 fence 검사→쓰기를 원자화하기 위한 비관적 락 단건 조회.
    // 무락 findById 로 읽고 메모리에서 attempt 를 검사한 뒤 dirty checking 으로 쓰면, 검사와 쓰기 사이에 recover 의
    // 다른 시도의 소유권 획득(attempt++)이 커밋돼 좀비가 새 시도의 행을 덮을 수 있다(ItemSnapshot 은 @Version 없음). FOR UPDATE 로
    // 로드하면 recover 의 FOR UPDATE(SKIP LOCKED)·acquireOwnership/renewOwnership UPDATE 와 같은 행 락에서 자연 직렬화되어, 검사와
    // 전이 write 가 한 락 구간 안에 묶인다. 단건 PK 락 + 짧은 트랜잭션이라 비용은 무시 가능하다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ItemSnapshot s where s.id = :id and s.deletedAt is null")
    fun findByIdForUpdate(
        @Param("id") id: Long,
    ): ItemSnapshot?

    // 살아있는 행만 id 목록으로 일괄 조회.
    fun findByIdInAndDeletedAtIsNull(ids: Collection<Long>): List<ItemSnapshot>

    // 공유 등록(#825 활성화)의 합류 판정 — 이 item 에 진행 중(PENDING/PROCESSING) 버전이 있으면 새 작업을 만들지
    // 않고 그 진행에 붙는다. 최신 우선(id desc)으로 하나만.
    @Query(
        "select s from ItemSnapshot s where s.itemId = :itemId and s.status in (com.depromeet.piki.item.domain.ItemStatus.PENDING, com.depromeet.piki.item.domain.ItemStatus.PROCESSING) and s.deletedAt is null order by s.id desc limit 1",
    )
    fun findLatestInProgressByItemId(
        @Param("itemId") itemId: Long,
    ): ItemSnapshot?

    // 공유 등록의 신선도 재사용 판정 — 마지막 **기계**(SERVER/SERVER_LLM) READY 버전. 수기(MANUAL)는 카드·추적이
    // 믿지 않는 값이라 재사용 대상이 아니고, 출처 null(도입 전 행)은 forward-only 라 별칭 경로에 닿지 않는다.
    @Query(
        "select s from ItemSnapshot s where s.itemId = :itemId and s.status = com.depromeet.piki.item.domain.ItemStatus.READY and s.source in (com.depromeet.piki.item.domain.ItemSnapshotSource.SERVER, com.depromeet.piki.item.domain.ItemSnapshotSource.SERVER_LLM) and s.deletedAt is null order by s.id desc limit 1",
    )
    fun findLatestMachineReadyByItemId(
        @Param("itemId") itemId: Long,
    ): ItemSnapshot?

    // 카드 표시값 파생(#857)의 배치 조회 1단계 — item 별 마지막 기계(SERVER/SERVER_LLM) READY 의 **id 만** 고른다.
    // 출처 null(도입 전 행)은 기계 여부를 모르므로 제외한다 — 그 item 은 호출부가 포인터 버전으로 fallback 한다.
    //
    // 엔티티 조회(2단계)를 한 문장에 합치지 않는다. 합치면(`where s.id in (select max(s2.id) ...)`) MySQL 이
    // 서브쿼리를 임시 테이블로 materialize 한 뒤 **바깥 테이블 전체를 훑으며 매 행을 대조**하는 계획을 고른다
    // (부하테스트 #911 실측: 서브쿼리는 19행/76ms 로 정확한데 바깥이 10만 행 스캔, 합계 483ms).
    // id 목록을 앱으로 받아 2단계에서 상수 IN 으로 넘기면 양쪽 모두 인덱스를 탄다. 왕복이 하나 늘지만
    // 1단계가 0.24ms 라 그 비용을 크게 밑돈다.
    @Query(
        "select max(s2.id) from ItemSnapshot s2 where s2.itemId in :itemIds " +
            "and s2.status = com.depromeet.piki.item.domain.ItemStatus.READY " +
            "and s2.source in (com.depromeet.piki.item.domain.ItemSnapshotSource.SERVER, com.depromeet.piki.item.domain.ItemSnapshotSource.SERVER_LLM) " +
            "and s2.deletedAt is null group by s2.itemId",
    )
    fun findLatestMachineReadyIdsByItemIds(
        @Param("itemIds") itemIds: Collection<Long>,
    ): List<Long>

    // 병합(#825) — 진(임시) item 의 모든 버전을 이긴 item 소속으로 재부모화한다. wish·tournament_item 은 snapshot 만
    // 참조하므로 이 한 문장으로 참조가 자동 추종된다. native bulk 라 auditing 을 우회해 updated_at 을 직접 갱신한다.
    @Modifying
    @Query(
        value = "UPDATE item_snapshots SET item_id = :toItemId, updated_at = NOW(6) WHERE item_id = :fromItemId",
        nativeQuery = true,
    )
    fun reparentAll(
        @Param("fromItemId") fromItemId: Long,
        @Param("toItemId") toItemId: Long,
    ): Int

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

    // recover 가 집을 stale 작업 — updated_at(집기·소유권 획득·박동 중 마지막 시각)이 threshold 이전인 PROCESSING snapshot 을
    // limit 개, FOR UPDATE SKIP LOCKED. created_at 이 아니라 updated_at 기준이라, PENDING 으로 오래 갇혔다 방금 claim 된 행은
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

    // 마감(deadline) 초과 행 — created_at 이 threshold 이전인 비-터미널(PENDING·PROCESSING) snapshot 을 limit 개, FOR UPDATE SKIP LOCKED.
    // stale 스캔(updated_at)과 다른 시계를 본다: updated_at 은 박동·집기가 계속 밀어 "살아있음"을 뜻하는 반면, created_at 은
    // 움직이지 않아 "이 작업이 얼마나 오래 끌고 있나"를 답한다. 그래서 박동이 멀쩡히 뛰는 느린 실행도, 슬롯이 없어 집히지 못한
    // PENDING 도 이 스캔에는 걸린다 — 종결을 보증하는 최후 시계다. idx_item_snapshots_status_created_at 이 커버한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = SKIP_LOCKED))
    @Query(
        "select s from ItemSnapshot s where s.status in :statuses and s.createdAt < :threshold and s.deletedAt is null " +
            "order by s.createdAt asc, s.id asc",
    )
    fun findOverdueForUpdate(
        @Param("statuses") statuses: Collection<ItemStatus>,
        @Param("threshold") threshold: LocalDateTime,
        pageable: Pageable,
    ): List<ItemSnapshot>

    // 소유권 획득 — 워커가 실행에 진입하는 순간 attemptCount 를 +1 하며 이 시도의 토큰을 확정한다.
    // 조건(status·직전 attempt 일치)이 원자적 test-and-set 이라, 같은 행에 두 워커가 제출돼도 하나만 1행을 받아 실행하고
    // 나머지는 0행으로 튕긴다. **집기·되살림이 아니라 여기서만 시도가 소모되므로**, 제출이 거부돼 실행이 0회면 예산도 안 준다.
    // @Modifying bulk update 라 JPA auditing 을 우회하므로 updated_at 을 명시로 넘긴다(획득 시점부터 stale 시계 재시작).
    // 반환은 영향받은 행 수(1=획득 성공, 0=이미 남이 가져갔거나 종결됨).
    @Modifying
    @Query(
        "update ItemSnapshot s set s.attemptCount = s.attemptCount + 1, s.updatedAt = :now " +
            "where s.id = :id and s.status = :status and s.attemptCount = :expectedAttempt and s.deletedAt is null",
    )
    fun acquireOwnership(
        @Param("id") id: Long,
        @Param("status") status: ItemStatus,
        @Param("expectedAttempt") expectedAttempt: Int,
        @Param("now") now: LocalDateTime,
    ): Int

    // 소유권 갱신(박동) — 이 snapshot 이 여전히 :attempt 의 PROCESSING 일 때만 updated_at 을 :now 로 민다. attempt 는 안 건드린다.
    // 산 워커가 도는 동안 recover 의 stale 판정 시각을 계속 갱신해, 느린 단건을 stale 로 오판해 죽이지 않게 한다.
    // fencing(status·attempt 일치)이 소유권을 건다: 소유권이 넘어갔거나 이미 READY/FAILED 로 전이한 행은 0행 매치라,
    // 좀비 워커의 박동이 남의 시도를 되살리지 못한다. auditing 우회는 acquireOwnership 과 같다.
    // 반환은 영향받은 행 수(1=소유권 유지, 0=상실).
    @Modifying
    @Query(
        "update ItemSnapshot s set s.updatedAt = :now " +
            "where s.id = :id and s.status = :status and s.attemptCount = :attempt and s.deletedAt is null",
    )
    fun renewOwnership(
        @Param("id") id: Long,
        @Param("status") status: ItemStatus,
        @Param("attempt") attempt: Int,
        @Param("now") now: LocalDateTime,
    ): Int
}
