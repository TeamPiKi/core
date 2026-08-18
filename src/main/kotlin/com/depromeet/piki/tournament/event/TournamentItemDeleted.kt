package com.depromeet.piki.tournament.event

import com.depromeet.piki.common.event.NotificationEvent
import java.util.UUID

// 토너먼트 아이템 삭제 — 도메인 사실. actorId 는 삭제한 사용자(아이템 등록자 본인 또는 토너먼트 주최자).
// tournamentItemId 는 빠진 출전 아이템(클라가 그 항목만 제거하도록 알림 payload 에 실린다).
// snapshotId 는 그 아이템의 고정 snapshot — 알림 도메인이 여기서 상품명을 해석한다. tournament_item 은 삭제 직후
// soft delete 라 핸들러(AFTER_COMMIT)가 역조회로 못 닿지만, snapshot 은 공유 immutable 행이라 살아 있다.
data class TournamentItemDeleted(
    val tournamentId: Long,
    val tournamentItemId: Long,
    val snapshotId: Long,
    val actorId: UUID,
) : NotificationEvent
