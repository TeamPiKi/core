package com.depromeet.piki.item.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

// 실행 중인 파싱 작업의 "살아 있음"을 주기적으로 DB 에 새겨, recover 의 stale 판정이 산 워커를 죽음으로 오판하지 않게 한다.
//
// 배경: recover 는 updated_at 이 오래 조용한 PROCESSING 을 "죽었다"고 보고 재실행한다. 예전엔 "단건 ≤60s" 라는 시간 추정에
// 기댔지만, (a) 한 시도의 실제 소요가 60s 를 넘거나 (b) 워커 풀 포화로 claim 후 큐 대기가 길면 산 작업이 stale 로 오판돼
// 중복 실행됐다. 이제 산 워커가 박동으로 updated_at 을 계속 갱신하므로, stale = "프로세스 죽음(박동 연속 누락)" 만 남는다.
//
// 2층 방어: 산 워커는 박동이 지키고(이 클래스), 소유권 잃은 좀비는 획득·박동의 0행 매치가 막는다(ParsingOwnership).
// **무한 행잉은 이 클래스가 책임지지 않는다** — 마감(ItemParsingScheduler.DEADLINE_MINUTES, created_at 기준)이 벽시계로
// 끊는다. 박동이 아무리 성실해도 마감은 종결하고, 종결되면 renew 가 0행이 되어 아래 레지스트리 정리까지 함께 일어난다.
// (박동에 별도 절대 캡을 두던 층이 있었으나, 마감 도입 후 그 캡의 회수 시각이 항상 마감보다 늦어 도달 불가능해져 걷어냈다.)
//
// 레지스트리 키는 snapshotId 다. 되살림으로 같은 snapshot 의 옛 시도와 새 시도가 잠깐 공존할 수 있어(죽은 줄 알았던
// 워커가 실은 늦게 살아 돌아오는 경우), register 는 더 최신 시도(높은 attempt)를 유지하고, deregister 는 자기 attempt 의
// 등록만 지운다 — 옛 시도의 종료가 산 새 시도의 박동을 지우지 않게 한다.
// fencing 이 정합성을 보장하므로 레지스트리 경합은 효율만 건드린다.
@Component
class ParsingHeartbeat(
    private val parsingOwnership: ParsingOwnership,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // snapshotId → 이 실행이 쥔 fencing 토큰(attempt). 박동은 이 토큰이 여전히 유효할 때만 먹힌다.
    private val registry = ConcurrentHashMap<Long, Int>()

    // 워커가 parse() 시작 시 등록한다. 같은 snapshot 에 더 최신 시도(높은 attempt)가 이미 있으면 그걸 유지한다 —
    // 옛 시도가 뒤늦게 등록해 산 새 시도의 박동을 덮어쓰는 것을 막는다.
    fun register(
        snapshotId: Long,
        attempt: Int,
    ) {
        registry.merge(snapshotId, attempt) { old, new -> maxOf(old, new) }
    }

    // 워커 종료(finally). 내 attempt 의 등록만 지운다 — 다른(더 최신) 시도가 덮어쓴 상태면 그 등록을 남긴다.
    // remove(key, value) 는 키·값이 **둘 다** 일치할 때만 지우는 원자 연산이라 이 의미가 그대로 표현된다.
    fun deregister(
        snapshotId: Long,
        attempt: Int,
    ) {
        registry.remove(snapshotId, attempt)
    }

    // 워커의 단건 실행을 소유권·박동 수명주기로 감싼다 — 두 워커(URL·이미지)가 복제하던 뼈대를 한 곳에 캡슐화한다.
    //   소유권 획득 → 등록 → 본문 → 해제(finally).
    // 획득에 실패하면(이미 남이 가져갔거나 종결) body 를 실행하지 않고 onOwnershipLost 를 부른다 — 워커는 ext 호출·부수효과 없이 스킵한다.
    // body 는 획득한 토큰(attempt)을 받아 이후의 박동·전이에 실어 나른다. Observation 래핑·스킵 로그·전이는 각 워커가 소유하므로 콜백으로 받는다.
    fun guarded(
        snapshotId: Long,
        expectedAttempt: Int,
        onOwnershipLost: () -> Unit,
        body: (attempt: Int) -> Unit,
    ) {
        // 시도 소모는 이 한 줄에서만 일어난다 — 실행에 실제로 진입할 때. 제출이 거부돼 여기 못 오면 예산도 안 준다.
        val attempt = parsingOwnership.acquire(snapshotId, expectedAttempt) ?: return onOwnershipLost()
        register(snapshotId, attempt)
        try {
            body(attempt)
        } finally {
            deregister(snapshotId, attempt)
        }
    }

    // 박동 루프 — 등록된 각 실행 중 작업의 updated_at 을 fenced touch 로 민다. stale 임계(60s) ≥ 이 주기(15s) x 3 + 여유라,
    // 산 워커는 박동을 연속으로 놓치지 않는 한 stale 로 오판되지 않는다.
    // 항목별 runCatching 으로 격리한다 — 한 항목의 touch 가 DB 블립으로 던져도 그 사이클의 나머지 항목 박동이 통째로 스킵되지 않는다.
    // 실패는 warn 만 남기고 넘어간다(레지스트리 유지 → 다음 사이클이 재시도).
    @Scheduled(fixedDelay = BEAT_INTERVAL_MS)
    fun beat() {
        registry.forEach { (snapshotId, attempt) ->
            runCatching { beatOne(snapshotId, attempt) }
                .onFailure { e -> log.warn("snapshot {} 박동 실패 — 다음 사이클 재시도 (attempt={}): {}", snapshotId, attempt, e.message) }
        }
    }

    private fun beatOne(
        snapshotId: Long,
        attempt: Int,
    ) {
        if (parsingOwnership.renew(snapshotId, attempt) == 0) {
            // 0행 = 소유권 없음(되살림으로 넘어갔거나 이미 READY/FAILED/마감 종결). 좀비 박동을 멈추고 레지스트리를 비운다 —
            // 행잉 실행의 등록이 영원히 남지 않는 것도 이 경로가 보장한다(마감이 반드시 종결시키므로 renew 는 언젠가 0행이 된다).
            registry.remove(snapshotId, attempt)
            log.info("snapshot {} 박동 대상 아님(되살림·이미 전이) — 레지스트리 제거 (attempt={})", snapshotId, attempt)
        }
    }

    // 테스트가 레지스트리 상태를 관측하는 최소 표면.
    internal fun isTracking(snapshotId: Long): Boolean = registry.containsKey(snapshotId)

    companion object {
        // 박동 주기. stale 임계(ItemParsingScheduler.STALE_TIMEOUT_SECONDS = 60s) ≥ 이 주기 x 3 + 여유를 지킨다.
        const val BEAT_INTERVAL_MS = 15_000L
    }
}
