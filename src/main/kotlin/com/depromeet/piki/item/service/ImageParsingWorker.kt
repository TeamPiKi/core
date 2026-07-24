package com.depromeet.piki.item.service

// PENDING item 의 이미지 파싱을 백그라운드로 수행하는 경계.
// 디스패처가 claim 한 작업을 받아 비동기로 파싱해 item 을 READY/FAILED 로 전이시킨다(일시 오류는 PROCESSING 유지 → recover 재시도).
// 입력은 등록 시 S3 에 durable 적재한 raw 이미지 object key 다 — 워커가 그 key 로 원본을 다시 읽어 파싱하므로,
// @Async 유실·일시 오류로 재실행돼도 원본이 살아 있다. ItemParsingWorker(URL 기반)와 분리해 두 경로가 독립적으로 교체·확장 가능하다.
interface ImageParsingWorker {
    // attempt 는 claim 의 fencing 토큰(ClaimedItem.attempt) — 워커가 시작 가드·상태 전이에 실어 좀비 결과를 걸러낸다.
    fun parse(
        itemId: Long,
        snapshotId: Long,
        imageKey: String,
        attempt: Int,
    )
}
