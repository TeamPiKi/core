package com.depromeet.piki.tournament.event

import com.depromeet.piki.common.event.NotificationEvent
import java.util.UUID

// 주최자가 자기 토너먼트에서 자기 판을 완료해 결과가 나온 사실 — 도메인 사실. recordMatch 결승 시점이며,
// 완료한 참여 행이 주최자 행일 때만 발행한다. rootTournamentId 는 그 토너먼트, actorId 는 주최자.
// 수신자(참여자 − 주최자: 초대 멤버 + 플레이 링크 참여자)에게 "참여하신 OO님의 토너먼트 결과가 나왔어요" 를 알린다.
data class TournamentResultReady(
    val rootTournamentId: Long,
    val actorId: UUID,
) : NotificationEvent
