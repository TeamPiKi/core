package com.depromeet.piki.item.service

// PENDING item 의 이미지 파싱을 백그라운드로 수행하는 경계.
// 디스패처가 claim 한 작업을 받아 비동기로 파싱해 item 을 READY/FAILED 로 전이시킨다(일시 오류는 소유권 반납 → 다시 집힘).
// 입력은 등록 시 S3 에 durable 적재한 raw 이미지 object key 다 — 워커가 그 key 로 원본을 다시 읽어 파싱하므로,
// @Async 유실·일시 오류로 재실행돼도 원본이 살아 있다. ItemParsingWorker(URL 기반)와 분리해 두 경로가 독립적으로 교체·확장 가능하다.
interface ImageParsingWorker {
    // expectedAttempt 는 소유권을 획득할 때 기대하는 **직전** attemptCount(ClaimedItem.expectedAttempt)다.
    // 워커는 이 값으로 조건부 +1 을 시도해 소유권을 얻고, 그렇게 얻은 토큰(expectedAttempt + 1)을 박동·전이에 실어 좀비 결과를 걸러낸다.
    fun parse(
        itemId: Long,
        snapshotId: Long,
        imageKey: String,
        expectedAttempt: Int,
    )
}
