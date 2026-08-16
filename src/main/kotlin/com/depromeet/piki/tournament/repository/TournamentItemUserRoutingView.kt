package com.depromeet.piki.tournament.repository

import java.util.UUID

// 이 버전(snapshot)을 pin 한 출전의 (등록자, 토너먼트, 출전 아이템 id) — 파싱 알림의 수신자별 토너먼트 딥링크
// 역조회용(#933). userId 를 함께 실어 "누가 어느 좌표로 가는가"를 수신자별로 가른다(기존 TournamentItemRoutingView
// 는 userId 가 없어 이벤트-단위 라우팅만 가능했다). Spring Data interface projection.
interface TournamentItemUserRoutingView {
    val userId: UUID
    val tournamentId: Long
    val tournamentItemId: Long
}
