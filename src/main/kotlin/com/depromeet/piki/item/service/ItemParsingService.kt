package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.event.ItemParsingCompleted
import com.depromeet.piki.item.event.ItemParsingFailed
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.product.service.ProductSnapshot
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

// 파싱 결과의 상태 전이만 짧은 트랜잭션으로 영속화한다 (전이는 dirty checking 으로 커밋 시 반영).
// 외부 호출(extract)은 워커가 트랜잭션 바깥에서 끝낸다. 워커(@Async)·디스패처(@Scheduled)와 별도 빈으로 두어
// AOP proxy 를 거치게 한다(self-invocation 회피).
//
// 추출값·상태는 ItemSnapshot 이 보유하므로 전이도 snapshot 단독으로 한다 — item(정체성)은 건드리지 않는다.
@Service
class ItemParsingService(
    private val itemRepository: ItemRepository,
    private val itemSnapshotRepository: ItemSnapshotRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 반환값은 **이 전이가 실제로 적용됐는지** 다. false(좀비 폐기)면 호출부는 자기 결과를 반영된 것으로 세면 안 된다 —
    // 특히 이미지 워커의 raw 원본 회수는 반드시 이 값으로 막아야 한다(소유권을 쥔 새 시도가 그 원본으로 재실행하므로).
    @Transactional
    fun markReady(
        snapshotId: Long,
        snapshot: ProductSnapshot,
        expectedAttempt: Int,
    ): Boolean {
        // 워커가 claim 한 그 snapshot 을 id 로 직접 전이한다 — findLatestByItemId(최신)가 아니다.
        // 갱신(5단계)으로 한 item 에 여러 버전이 공존하면 "최신"이 이 워커가 추출한 행과 다를 수 있어(stale/좀비 워커가
        // 다른 버전을 오전이), claim 시점에 고정한 snapshotId 로 정확히 짚는다. 없으면 영속화 경로가 깨진 코드 버그다.
        // FOR UPDATE 로 로드해 fence 검사→전이 write 를 한 행 락 구간으로 원자화한다(무락 read 와 write 사이 소유권 이전 커밋 방지).
        val target =
            itemSnapshotRepository.findByIdForUpdate(snapshotId)
                ?: error("파싱 대상 snapshot $snapshotId 이 없다")
        if (isZombieResult(target, expectedAttempt)) return false
        target.markReady(snapshot)
        // 트랜잭션 안에서 발행 → AFTER_COMMIT 리스너가 커밋 성공 후에만 알림을 보낸다 (롤백 시 발송 안 됨). itemId 는 snapshot 단일 출처.
        eventPublisher.publishEvent(ItemParsingCompleted(target.itemId, target.getId()))
        return true
    }

    // markReady 와 같이 적용 여부를 돌려준다 (false = 좀비 폐기).
    @Transactional
    fun markFailed(
        snapshotId: Long,
        expectedAttempt: Int,
    ): Boolean {
        // markReady 와 같은 이유로 FOR UPDATE 로드 — fence 검사와 종결 write 를 원자화한다.
        val target =
            itemSnapshotRepository.findByIdForUpdate(snapshotId)
                ?: error("파싱 대상 snapshot $snapshotId 이 없다")
        if (isZombieResult(target, expectedAttempt)) return false
        target.markFailed()
        eventPublisher.publishEvent(ItemParsingFailed(target.itemId, target.getId()))
        return true
    }

    // 소유권 반납 — 일시 외부 오류로 이번 실행이 결론 없이 끝났을 때 워커가 부른다. PROCESSING → PENDING 으로 되돌려
    // 디스패처가 다음 tick(1s)에 곧바로 다시 집게 한다.
    //
    // 반납이 없으면 다음 실행은 stale 판정(마지막 박동 + 임계 60s)을 기다려야 한다. 박동이 "산 워커를 지키는" 대가로
    // 죽음 감지가 워커 사망 시각 기준으로 밀렸기 때문인데, 실행이 스스로 끝났다는 걸 아는 그 순간 반납하면 그 지연이 사라진다.
    // 그 결과 stale 되살림은 본래 의미(**프로세스가 죽어 아무도 반납해 주지 못한 경우**)만 남는다.
    //
    // 반환값은 markReady/markFailed 와 같은 계약이다 — false 면 좀비라 아무것도 반영되지 않았다.
    @Transactional
    fun release(
        snapshotId: Long,
        expectedAttempt: Int,
    ): Boolean {
        // 전이 계열과 같은 이유로 FOR UPDATE 로드 — fence 검사와 되돌림 write 를 한 락 구간으로 원자화한다.
        val target =
            itemSnapshotRepository.findByIdForUpdate(snapshotId)
                ?: error("파싱 대상 snapshot $snapshotId 이 없다")
        if (isZombieResult(target, expectedAttempt)) return false
        // 예산을 다 쓴 실행을 반납하면 무한 재큐잉이 된다 — 되살림 경로와 같은 판정·같은 reason 으로 여기서 종결한다.
        if (target.attemptCount >= MAX_ATTEMPTS) {
            target.markFailed()
            eventPublisher.publishEvent(ItemParsingFailed(target.itemId, target.getId()))
            ItemParsingMetrics.record(meterRegistry, ItemParsingMetrics.RESULT_FAILED, ItemParsingMetrics.REASON_RETRY_EXHAUSTED)
            return true
        }
        target.release()
        return true
    }

    // fencing — 로드한 snapshot 의 attemptCount 가 워커가 획득한 토큰(expectedAttempt)과 어긋나면, 실행 도중 소유권이
    // 다른 시도로 넘어간 좀비 워커의 결과다. 전이 없이 폐기(로그만)해, 옛 시도가 새 시도의 행을 오전이·오종결하지 못하게 한다. true=폐기.
    // recover 내부의 FAILED 종결·마감 종결은 소유권 회수 행위 자체라 이 fencing 을 타지 않는다(entity 전이를 직접 호출한다).
    private fun isZombieResult(
        target: ItemSnapshot,
        expectedAttempt: Int,
    ): Boolean {
        if (target.attemptCount == expectedAttempt) return false
        log.info(
            "item {} snapshot {} 좀비 결과 폐기 — 소유권 attempt 불일치(expected={} actual={})",
            target.itemId,
            target.getId(),
            expectedAttempt,
            target.attemptCount,
        )
        return true
    }

    // 디스패처가 PENDING 작업을 집어 PROCESSING 으로 claim 한다 (짧은 트랜잭션 + FOR UPDATE 락).
    // 실제 파싱(외부 LLM, 트랜잭션 밖)은 디스패처가 반환받은 ClaimedItem 으로 워커에 넘긴다.
    //
    // batch 전체가 한 트랜잭션이므로 한 행에서 throw 하면 batch 전체가 롤백되고, FIFO 라 같은 선두 batch 가
    // 매 tick 재fetch 돼 poison-pill 로 디스패치가 영구 정지한다. 따라서 이상 행도 throw 없이 처리한다:
    //   - snapshot 은 FOR UPDATE 로 PENDING 으로 잠겨 있어 markProcessing 은 throw 하지 않는다.
    //   - link 없는 PENDING(정상 흐름엔 없음 — URL 등록만 PENDING 이고 항상 link 보유)은 markProcessing 으로
    //     PENDING 큐에서 빼되 워커에 안 넘긴다 → recover 가 stale 로 잡아 FAILED 로 종결한다(영구 PENDING 방지).
    @Transactional
    fun claimDuePending(batchSize: Int): List<ClaimedItem> {
        val snapshots = itemSnapshotRepository.findDuePending(batchSize)
        if (snapshots.isEmpty()) return emptyList()
        // per-snapshot N+1 대신 item 을 한 번에 로드한다 (snapshot 은 itemId 만 들고 입력(link/imageKey)은 item 소관).
        val itemById = itemRepository.findByIds(snapshots.map { it.itemId }).associateBy { it.getId() }
        return snapshots.mapNotNull { snapshot ->
            snapshot.markProcessing()
            // markProcessing 은 attemptCount 를 건드리지 않으므로 현재값이 곧 "획득 시 기대하는 직전 값"이다.
            toClaim(snapshot, itemById[snapshot.itemId], snapshot.attemptCount)
        }
    }

    // stale PROCESSING(프로세스가 죽어 박동이 끊긴 행)을 집어 재실행 또는 종결한다.
    // claim-at-least-once 를 execution at-least-once 로 끌어올리는 핵심(#461) — 기존의 "무조건 FAILED" 를 "재실행 우선"으로 바꿨다.
    //
    // stale 판정은 updated_at 기준이다. 산 워커는 ParsingHeartbeat 가 박동으로 updated_at 을 계속 갱신하고, 일시 오류로
    // 스스로 끝난 실행은 release 로 즉시 반납하므로, updated_at 이 threshold 보다 오래됐다는 건 "박동이 연속으로 끊겼는데
    // 반납도 없었다 = 프로세스가 죽었다" 는 뜻이다(더는 "단건 ≤60s" 시간 추정에 기대지 않는다).
    // 그런 행을:
    //   - link·imageKey 가 둘 다 없으면(입력 없는 orphan) 되살릴 수 없으므로 즉시 FAILED. 이미지(imageKey)는 S3 raw 로 durable 해 link 처럼 재실행한다.
    //   - attempt 가 실행 상한(MAX_ATTEMPTS)에 도달했으면 더 시도하지 않고 FAILED (무한 재큐잉 방지).
    //   - 그 외에는 되살림 대상으로 지목해 반환한다(DB 는 그대로) — 워커 제출은 스케줄러가 트랜잭션 밖에서 하고, attempt 는 워커가 실행에 진입할 때 소모한다.
    //
    // 되살림은 reviveSlots(호출부의 가용 워커 슬롯)만큼만 지목한다 — 제출도 못 할 지목은 로그만 늘린다. 미룬 행은
    // 아무것도 안 건드리므로 다음 사이클이 그대로 다시 집는다. 반면 **종결(FAILED)은 reviveSlots 와 무관하게 진행**한다 —
    // 워커 슬롯이 필요 없는 판정이라, 슬롯으로 막으면 풀이 오래 포화일 때 종결이 영영 밀린다.
    //
    // snapshot 은 FOR UPDATE 로 PROCESSING 으로 잠겨 markFailed 가 throw 하지 않으므로 batch poison 이 없다.
    @Transactional
    fun reviveOrFailStale(
        threshold: LocalDateTime,
        batchSize: Int,
        reviveSlots: Int,
    ): StaleProcessingOutcome {
        val stale = itemSnapshotRepository.findStaleProcessing(threshold, batchSize)
        if (stale.isEmpty()) return StaleProcessingOutcome(emptyList(), 0)
        // per-snapshot N+1 대신 item 을 한 번에 로드한다 (snapshot 은 itemId 만 들고 입력(link/imageKey)은 item 소관).
        val itemById = itemRepository.findByIds(stale.map { it.itemId }).associateBy { it.getId() }
        val toRevive = mutableListOf<ClaimedItem>()
        var failedCount = 0
        stale.forEach { snapshot ->
            // 되살릴 입력(link/imageKey)이 없으면(둘 다 부재 = orphan, 또는 item 부재) 종결. toClaim 이 null 로 일괄 판정한다.
            // 지목은 attemptCount 를 안 올리므로 현재값을 그대로 실어 보낸다 — 워커가 실행에 진입하며 이 값으로 +1 을 시도한다.
            val claim =
                toClaim(snapshot, itemById[snapshot.itemId], snapshot.attemptCount) ?: run {
                    snapshot.markFailed()
                    eventPublisher.publishEvent(ItemParsingFailed(snapshot.itemId, snapshot.getId()))
                    ItemParsingMetrics.record(meterRegistry, ItemParsingMetrics.RESULT_FAILED, ItemParsingMetrics.REASON_NO_SOURCE)
                    failedCount++
                    return@forEach
                }
            // 실행 상한 도달: 더 되살리지 않고 종결.
            if (snapshot.attemptCount >= MAX_ATTEMPTS) {
                snapshot.markFailed()
                eventPublisher.publishEvent(ItemParsingFailed(snapshot.itemId, snapshot.getId()))
                ItemParsingMetrics.record(meterRegistry, ItemParsingMetrics.RESULT_FAILED, ItemParsingMetrics.REASON_RETRY_EXHAUSTED)
                failedCount++
                return@forEach
            }
            // 가용 슬롯 소진: 이번 사이클엔 지목하지 않는다. 다음 사이클이 같은 행을 다시 집는다.
            if (toRevive.size >= reviveSlots) return@forEach
            // 되살림 = 지목뿐. DB 는 건드리지 않는다 — 소유권(attempt)은 워커가 실행에 진입할 때 스스로 가져간다.
            toRevive.add(claim)
        }
        return StaleProcessingOutcome(toRevive, failedCount)
    }

    // 마감(deadline) 초과 종결 — created_at 이 threshold 이전인 비-터미널 행을 FAILED 로 끝낸다. 종결한 건수를 반환한다.
    //
    // attempt 예산과 별개인 **벽시계** 판정이다. attempt 는 "실행을 몇 번 했나"(예산)를, 이 마감은 "얼마나 오래 끌 수 있나"를
    // 답한다. 예전엔 이 둘이 한 숫자에 얽혀 있어서, 실행하지도 않은 제출 거부가 종결 시점까지 앞당기는 불공정이 있었다.
    // 마감은 박동과도 무관해 "박동은 멀쩡한데 너무 느린 실행"도, "슬롯이 없어 집히지 못한 PENDING"도 함께 종결한다.
    @Transactional
    fun failOverdue(
        threshold: LocalDateTime,
        batchSize: Int,
    ): Int {
        val overdue = itemSnapshotRepository.findOverdue(threshold, batchSize)
        overdue.forEach { snapshot ->
            snapshot.expire()
            eventPublisher.publishEvent(ItemParsingFailed(snapshot.itemId, snapshot.getId()))
            ItemParsingMetrics.record(meterRegistry, ItemParsingMetrics.RESULT_FAILED, ItemParsingMetrics.REASON_DEADLINE)
        }
        return overdue.size
    }

    // snapshot 의 item 입력(link XOR imageKey)으로 claim 객체를 만든다. link 우선, 없으면 imageKey, 둘 다 없으면
    // (입력 없는 orphan 또는 item 부재) null — claim 경로는 워커에 안 넘기고(다음 recover 가 stale 로 잡아 FAILED),
    // recover 경로는 즉시 FAILED 한다. 정상 흐름(URL·이미지 등록)엔 항상 입력이 있어 null 은 영속화 경로가 깨진 신호다.
    // expectedAttempt 는 소유권 획득 시 기대하는 직전 attemptCount — 워커가 이 값으로 조건부 +1 을 시도한다.
    private fun toClaim(
        snapshot: ItemSnapshot,
        item: Item?,
        expectedAttempt: Int,
    ): ClaimedItem? {
        val resolved =
            item ?: run {
                log.error("snapshot {} 의 item {} 이 없어 claim 제외", snapshot.getId(), snapshot.itemId)
                return null
            }
        resolved.link?.let { return LinkClaim(snapshot.itemId, snapshot.getId(), it, expectedAttempt) }
        resolved.sourceImageKey?.let { return ImageClaim(snapshot.itemId, snapshot.getId(), it, expectedAttempt) }
        log.error("snapshot {} (item {}) 에 link·imageKey 둘 다 없어 claim 제외 (입력 없는 orphan)", snapshot.getId(), snapshot.itemId)
        return null
    }

    companion object {
        // **실행** 시도 상한(초회 1 + 재실행 1). 집기(claim)·되살림(revive)이 아니라 워커가 실행에 진입할 때만 소모되므로,
        // 제출이 거부돼 실행이 0회인 행은 이 예산을 잃지 않는다.
        //
        // 예산(실행 횟수)과 마감(시간)은 서로 다른 질문에 답한다 — "얼마나 오래 끌 수 있나"는 이 값이 아니라
        // ItemParsingScheduler.DEADLINE_MINUTES 가 답한다. 상한을 소진하는 두 경로(반납 release·되살림 revive)가
        // 같은 판정을 써야 하므로 스케줄러가 아니라 이 서비스가 정본을 쥔다.
        const val MAX_ATTEMPTS = 2
    }
}
