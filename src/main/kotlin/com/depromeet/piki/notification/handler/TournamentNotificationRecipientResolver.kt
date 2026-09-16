package com.depromeet.piki.notification.handler

import com.depromeet.piki.tournament.repository.TournamentRepository
import com.depromeet.piki.tournament.repository.TournamentUserRepository
import org.springframework.stereotype.Component
import java.util.UUID

// 플레이링크/완료/결과 알림의 수신자를 ROOT 토너먼트 기준으로 역조회한다. 세 핸들러가 공유하는 단일 도출 지점이라
// "ROOT 주최자가 누구냐 / 결과 참여자가 누구냐" 규칙을 여기 한 곳에 둔다 (ItemParsingRecipientResolver 와 같은 결).
//
// 못 찾으면(데이터 불일치) 빈 집합을 돌려 알림을 떨군다 — 알림은 best-effort 라 수신자 한 명을 못 풀었다고 던지지 않는다.
// actor(주최자 본인) 제외는 호출하는 핸들러가 `- event.actorId` 로 적용한다(수신자 규칙과 제외 규칙을 한 곳에 묶지 않는다).
@Component
class TournamentNotificationRecipientResolver(
    private val tournamentRepository: TournamentRepository,
    private val tournamentUserRepository: TournamentUserRepository,
) {
    // 플레이/완료 알림 수신자 = ROOT 주최자 1명. ROOT 의 ownerTournamentUserId → 그 TournamentUser 의 userId.
    fun rootOwner(rootTournamentId: Long): Set<UUID> {
        val root = tournamentRepository.findTournamentById(rootTournamentId) ?: return emptySet()
        return tournamentUserRepository
            .findByIds(setOf(root.ownerTournamentUserId))
            .firstOrNull()
            ?.let { setOf(it.userId) }
            ?: emptySet()
    }

    // 결과 알림 수신자 = ROOT 참가자 전원(아이템 등록·초대 합류·플레이링크 게스트 포함).
    // #1027: 클론이 사라져 게스트도 ROOT 참여 행을 가지므로, ROOT 참여 행 전체가 곧 결과 수신 대상이다.
    // 토큰 있는 사람에게만 푸시가 가고, 없으면 히스토리에만 적재된다(#473 — GUEST 도 토너먼트 푸시 대상).
    fun resultParticipants(rootTournamentId: Long): Set<UUID> =
        tournamentUserRepository.findByTournamentId(rootTournamentId).map { it.userId }.toSet()
}
