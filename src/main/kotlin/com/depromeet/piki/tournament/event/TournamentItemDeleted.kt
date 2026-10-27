package com.depromeet.piki.tournament.event

import com.depromeet.piki.common.event.NotificationEvent
import java.util.UUID

// 토너먼트 아이템 삭제 — 도메인 사실. actorId 는 삭제한 사용자(아이템 등록자 본인 또는 토너먼트 주최자).
// tournamentItemId 는 빠진 출전 아이템(클라가 그 항목만 제거하도록 알림 payload 에 실린다).
// snapshotId 는 삭제 순간 보이던 버전. 파싱 전이면 없다. 알림이 여기서 상품명을 읽는다
data class TournamentItemDeleted(
    val tournamentId: Long,
    val tournamentItemId: Long,
    val snapshotId: Long?,
    val actorId: UUID,
) : NotificationEvent
