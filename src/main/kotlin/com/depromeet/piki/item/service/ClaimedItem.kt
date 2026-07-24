package com.depromeet.piki.item.service

import com.depromeet.piki.product.domain.ProductLink

// 디스패처가 PENDING → PROCESSING 으로 claim 한 작업. claim 트랜잭션 안에서 입력(link 또는 image key)을 꺼내 두어,
// 트랜잭션 밖에서 도는 워커가 detached 엔티티를 다시 만지지 않고 입력만으로 파싱하게 한다.
// 입력은 link XOR imageKey 다 — item 의 정체성이 둘 중 하나이고, 디스패처가 종류에 따라 알맞은 워커로 라우팅한다.
sealed interface ClaimedItem {
    val itemId: Long

    // 워커가 전이시킬 정확한 snapshot id. 갱신(5단계)으로 한 item 에 여러 버전이 공존하므로, 전이 대상을
    // findLatestByItemId(최신)로 재해석하지 않고 claim 시점에 고정한 이 id 로 짚는다(stale·좀비 워커의 오전이 방지).
    val snapshotId: Long

    // 이 claim 의 fencing 토큰 = claim(markProcessing) 또는 reclaim 직후의 attemptCount. 워커가 시작 가드·전이(markReady/markFailed)에
    // 이 값을 실어, 큐에 묵다 재클레임돼(attempt++) 소유권을 잃은 좀비 결과가 새 시도의 행을 오전이·오종결하지 못하게 한다.
    val attempt: Int
}

// URL 등록 경로의 claim — 원본 link 로 파싱한다(AsyncItemParsingWorker).
data class LinkClaim(
    override val itemId: Long,
    override val snapshotId: Long,
    val link: ProductLink,
    override val attempt: Int,
) : ClaimedItem

// 이미지 등록 경로의 claim — S3 raw object key 로 원본을 다시 읽어 파싱한다(AsyncImageParsingWorker).
data class ImageClaim(
    override val itemId: Long,
    override val snapshotId: Long,
    val imageKey: String,
    override val attempt: Int,
) : ClaimedItem

// recover 한 사이클의 결과. toRetry 는 재실행(reclaim)해 워커에 다시 넘길 작업, failedCount 는 이번에 종결(FAILED)한 건수.
// 재실행 디스패치는 트랜잭션 밖에서 스케줄러가 하므로(트랜잭션 안에서 외부 호출 금지), 서비스는 재실행 대상만 반환하고
// 실제 워커 제출은 호출부(ItemParsingScheduler)가 한다.
data class StaleProcessingOutcome(
    val toRetry: List<ClaimedItem>,
    val failedCount: Int,
)
