package com.depromeet.piki.tournament.event

import com.depromeet.piki.common.event.NotificationEvent
import java.util.UUID

// 플레이링크로 누군가 내 토너먼트를 플레이하기 시작한 사실 — 도메인 사실. createFromPlayLink 의 신규 클론 생성 시점이다.
// rootTournamentId 는 원본(ROOT) 토너먼트, actorId 는 링크로 플레이를 시작한 사람. 수신자(ROOT 주최자)에게 알려,
// 링크를 뿌린 주최자가 누가 참여했는지 앱을 일일이 안 열어봐도 알게 한다.
data class TournamentPlayedFromLink(
    val rootTournamentId: Long,
    val actorId: UUID,
) : NotificationEvent
