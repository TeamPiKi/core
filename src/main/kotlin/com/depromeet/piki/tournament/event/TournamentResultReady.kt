package com.depromeet.piki.tournament.event

import com.depromeet.piki.common.event.NotificationEvent
import java.util.UUID

// 주최자가 자기 토너먼트(ROOT)를 완료해 결과가 나온 사실 — 도메인 사실. recordMatch 결승 → complete() 시점이며,
// 완료된 토너먼트가 ROOT(sourceTournamentId 없음=주최자 본인 진행)일 때만 발행한다. rootTournamentId 는 그 ROOT, actorId 는 주최자.
// 수신자(참여자 − 주최자: ROOT 참가자 + 플레이링크 클론 소유자)에게 "참여하신 OO님의 토너먼트 결과가 나왔어요" 를 알린다.
data class TournamentResultReady(
    val rootTournamentId: Long,
    val actorId: UUID,
) : NotificationEvent
