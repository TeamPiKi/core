package com.depromeet.piki.tournament.repository

// 우승한 상품과 그 우승이 난 토너먼트 좌표 — 위시 카드의 우승 칩(#1236)이 역추적에 쓴다.
// 한 상품이 여러 판에서 이길 수 있어 itemId 당 여러 행이 나온다. Spring Data interface projection.
interface WinnerItemView {
    val itemId: Long
    val tournamentId: Long
}
