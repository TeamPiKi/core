package com.depromeet.piki.tournament.event

import com.depromeet.piki.common.event.NotificationEvent
import java.util.UUID

// 토너먼트 시작 — 도메인 사실. 주최자가 PENDING→IN_PROGRESS 로 전이시킨 순간이다. actorId 는 시작시킨 주최자.
// 수신자(참여자 − 주최자)에게 알려, 주최자의 시작 버튼만으로 나머지 참여자도 함께 토너먼트로 진입하게 한다 —
// 시작 시점은 클라가 알 방법이 없어 서버 push 로만 전달된다.
data class TournamentStarted(
    val tournamentId: Long,
    val actorId: UUID,
) : NotificationEvent
