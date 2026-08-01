package com.depromeet.piki.notification.service.dto

import java.util.UUID

// N일 자동삭제 1회 결과. deletedCount 는 지표·로그용, affectedUnreadByUser 는 배지 동기화용이다.
// affectedUnreadByUser: 삭제로 안읽음이 줄어든 유저(cutoff 미만 안읽음 보유자) → 삭제 후 재집계한 안읽음 수.
// 스케줄러가 이 맵을 돌며 유저별로 SSE silent-sync + FCM 배지를 쏴, 자동삭제도 단건·모두 삭제처럼 배지를 최신으로 맞춘다.
data class NotificationPurgeResult(
    val deletedCount: Int,
    val affectedUnreadByUser: Map<UUID, Long>,
)
