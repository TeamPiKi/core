package com.depromeet.piki.item.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

// 아이템 파싱 outbox 디스패처 + recover (URL·이미지 공용 — claim 종류로 워커를 라우팅한다).
//
// 등록은 PENDING snapshot 을 커밋만 하고(작업 적재), 작업의 진실 원천은 인메모리 큐가 아니라 DB 의 PENDING 행이다.
// 디스패처가 그 행을 집어 실행하므로, @Async 큐 유실(인스턴스 재시작 등)로 PENDING 이 방치되는 일이 없다 —
// PENDING 은 반드시 한 번은 claim 돼 실행이 시작된다. item_snapshots 테이블 자체가 outbox(상태머신을 가진 작업 큐)라
// 별도 테이블이 없다.
//
// 보장은 execution at-least-once(#461): claim 직후 크래시(실행 0회)·실행 중 크래시·일시 외부 오류로 단건 실행이
// 끝나지 않은 작업을 recover 가 재실행으로 되살린다. stale 판정을 지탱하는 것은 시간 추정이 아니라 ParsingHeartbeat 다:
//   1. 산 워커는 박동으로 updated_at 을 계속 갱신한다(박동 간격 15s). 그래서 updated_at 이 stale 임계(60s = 박동 x 3 + 여유)보다
//      오래된 PROCESSING 은 "박동이 연속으로 끊겼다 = 프로세스가 죽었다" 는 뜻이고, 정상적으로 (느리게라도) 도는 시도는 stale 로 오판하지 않는다.
//      좀비(재클레임돼 소유권 잃은 옛 시도)는 fenced touch·전이의 attempt fencing 이 막고, 무한 행잉은 박동의 절대 캡(5분)이 끊어 회수에 넘긴다.
//   2. 재실행 상한 2회(MAX_ATTEMPTS). 재실행 대상은 죽은 워커의 행이므로 상한과 함께 종결이 보장된다.
// 재시도해도 결과가 뻔한 확정 실패(상품 아님 등)는 워커가 즉시 FAILED 하고, recover 는 "실행이 안 끝난" 행만 맡는다.
//
// 단일 인스턴스 기준의 @Scheduled 다. claim 은 FOR UPDATE SKIP LOCKED 라(ItemSnapshotJpaRepository) 중복 파싱을
// 막으면서도 잠긴 행에 대기하지 않는다 — claim 스캔이 대기 에지로 끼는 InnoDB 교착(실측: 동시 locking 트랜잭션이
// 같은 행을 보조 인덱스와 PRIMARY 에서 반대 순서로 잠그는 사이클, victim 이 요청 쪽이면 간헐 500)을 제거했고,
// 멀티 인스턴스에서도 두 디스패처가 서로 다른 행을 집는다(work-queue 분산). 남는 보강은 ShedLock(폴링 단일 실행) 정도다.
@Component
class ItemParsingScheduler(
    private val itemParsingService: ItemParsingService,
    private val itemParsingWorker: ItemParsingWorker,
    private val imageParsingWorker: ImageParsingWorker,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 디스패처 — 짧은 주기로 PENDING 을 집어 PROCESSING 으로 claim(짧은 트랜잭션 + FOR UPDATE)한 뒤,
    // 실제 파싱(외부 LLM)은 트랜잭션 밖에서 워커 풀(@Async)에 넘긴다. 등록 후 파싱 시작 지연 = 이 폴링 주기.
    @Scheduled(fixedDelay = DISPATCH_INTERVAL_MS)
    fun dispatch() {
        val claimed = itemParsingService.claimDuePending(BATCH_SIZE)
        if (claimed.isEmpty()) return
        log.info("PENDING {}건 claim → 워커 디스패치", claimed.size)
        claimed.forEach { dispatchToWorker(it) }
    }

    // 디스패처·recover 가 공유하는 워커 제출. claim 종류(URL/이미지)로 알맞은 워커에 라우팅한다. 풀 포화(queue 초과)로 거부되면
    // PROCESSING 그대로 둔다 — recover 가 stale 로 잡아 재실행한다(execution at-least-once). "claim 됐는데 실행 0회" 도 되살릴 대상이라 종결하지 않는다.
    // (#411 까지는 거부 시 즉시 FAILED 였으나, 실패·재시도 판정을 recover 한 곳으로 모으면서 여기선 종결하지 않는다.)
    private fun dispatchToWorker(claimed: ClaimedItem) {
        runCatching {
            when (claimed) {
                is LinkClaim -> itemParsingWorker.parse(claimed.itemId, claimed.snapshotId, claimed.link, claimed.attempt)
                is ImageClaim -> imageParsingWorker.parse(claimed.itemId, claimed.snapshotId, claimed.imageKey, claimed.attempt)
            }
        }.onFailure { e -> log.warn("item {} 워커 디스패치 거부 → PROCESSING 유지, recover 가 재실행: {}", claimed.itemId, e.message) }
    }

    // recover — 프로세스 죽음으로 박동이 끊긴 stale PROCESSING 을 재실행하거나(execution at-least-once) 상한 도달·되살림 불가 시 종결한다.
    // stale 판정은 updated_at(박동·claim·재실행 시각) 기준이다. 산 워커는 박동이 updated_at 을 갱신하므로 60s 윈도에 걸리지 않고,
    // 걸린 행은 박동이 끊긴(죽은) 워커의 것이다.
    @Scheduled(fixedDelay = RECOVER_INTERVAL_MS)
    fun recover() {
        val threshold = LocalDateTime.now().minusSeconds(STALE_TIMEOUT_SECONDS)
        val outcome = itemParsingService.retryOrFailStaleProcessing(threshold, BATCH_SIZE, MAX_ATTEMPTS)
        if (outcome.toRetry.isNotEmpty()) {
            log.warn("stale PROCESSING {}건 재실행 디스패치", outcome.toRetry.size)
            outcome.toRetry.forEach { dispatchToWorker(it) }
        }
        if (outcome.failedCount > 0) {
            log.warn("stale PROCESSING {}건 → FAILED (재시도 상한 도달 또는 되살릴 수 없음)", outcome.failedCount)
        }
    }

    companion object {
        private const val BATCH_SIZE = 100

        // 사용자 대면 작업이라 짧게 둔다 — 등록 직후 파싱이 시작되기까지의 지연이 이 주기로 결정된다.
        private const val DISPATCH_INTERVAL_MS = 1_000L

        // recover 주기. stale 윈도(60s) + 이 주기가 stale 감지 지연이다. 산 워커는 박동이 지키므로 이 윈도에 걸리는 건 죽은 워커의 행이다.
        private const val RECOVER_INTERVAL_MS = 15_000L

        // stale 임계 — 이보다 오래 updated_at 이 조용한 PROCESSING 은 박동이 끊긴(죽은) 워커의 것으로 본다.
        // 박동 간격(ParsingHeartbeat.BEAT_INTERVAL_MS = 15s) x 3 + 여유라, 산 워커가 박동을 연속으로 놓치지 않는 한 오판되지 않는다.
        private const val STALE_TIMEOUT_SECONDS = 60L

        // 실행 시도 상한(초회 1 + 재시도 1). 재실행 대상은 죽은 워커의 행이라, 상한과 함께 종결이 보장된다.
        private const val MAX_ATTEMPTS = 2
    }
}
