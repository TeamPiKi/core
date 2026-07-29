package com.depromeet.piki.item.service

import com.depromeet.piki.product.domain.ProductLink

// 워커에게 넘길 작업 지목. 집기(claim, PENDING → PROCESSING)와 되살림(revive, stale PROCESSING 재지목) 양쪽이 만든다.
// 짧은 트랜잭션 안에서 입력(link 또는 image key)을 꺼내 두어, 트랜잭션 밖에서 도는 워커가 detached 엔티티를
// 다시 만지지 않고 입력만으로 파싱하게 한다.
// 입력은 link XOR imageKey 다 — item 의 정체성이 둘 중 하나이고, 디스패처가 종류에 따라 알맞은 워커로 라우팅한다.
sealed interface ClaimedItem {
    val itemId: Long

    // 워커가 전이시킬 정확한 snapshot id. 갱신(5단계)으로 한 item 에 여러 버전이 공존하므로, 전이 대상을
    // findLatestByItemId(최신)로 재해석하지 않고 claim 시점에 고정한 이 id 로 짚는다(stale·좀비 워커의 오전이 방지).
    val snapshotId: Long

    // 소유권 획득 시 기대하는 **직전** attemptCount (지목 시점의 현재값). 워커가 실행에 진입하며 이 값으로 조건부 +1 을
    // 시도해, 성공하면 토큰 expectedAttempt + 1 을 갖는다. 같은 행에 두 워커가 지목돼도 하나만 성공한다.
    // 지목 자체는 attemptCount 를 건드리지 않으므로, 제출이 거부돼 실행이 0회면 예산도 소모되지 않는다.
    val expectedAttempt: Int
}

// URL 등록 경로의 지목 — 원본 link 로 파싱한다(AsyncItemParsingWorker).
data class LinkClaim(
    override val itemId: Long,
    override val snapshotId: Long,
    val link: ProductLink,
    override val expectedAttempt: Int,
) : ClaimedItem

// 이미지 등록 경로의 지목 — S3 raw object key 로 원본을 다시 읽어 파싱한다(AsyncImageParsingWorker).
data class ImageClaim(
    override val itemId: Long,
    override val snapshotId: Long,
    val imageKey: String,
    override val expectedAttempt: Int,
) : ClaimedItem

// recover 한 사이클의 결과. toRevive 는 워커에 다시 넘길 지목, failedCount 는 이번에 종결(FAILED)한 건수.
// 되살림은 DB 를 건드리지 않는다 — 소유권(attempt)은 워커가 실행에 진입할 때 스스로 가져가므로, 제출이 거부되면
// 아무것도 소모되지 않고 다음 사이클이 같은 행을 다시 지목한다(자가 치유).
// 워커 제출은 트랜잭션 밖에서 스케줄러가 한다(트랜잭션 안에서 외부 호출 금지).
data class StaleProcessingOutcome(
    val toRevive: List<ClaimedItem>,
    val failedCount: Int,
)
