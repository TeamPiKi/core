package com.depromeet.piki.notification.handler

import com.depromeet.piki.tournament.repository.TournamentRepository
import com.depromeet.piki.tournament.repository.TournamentUserRepository
import org.springframework.stereotype.Component
import java.util.UUID

// 토너먼트 알림 6종이 공유하는 템플릿 변수·프사 도출. actorName(닉네임)·tournamentId·tournamentName 을 한 번의
// actor 조회 + 토너먼트 조회로 채운다. 핸들러마다 중복 배선하지 않게 한 곳에 모은다 — 변수 카탈로그
// (NotificationTemplateVariables)가 선언한 것과 여기서 채우는 키가 항상 일치해야 한다(백오피스 검증·미리보기의 전제).
@Component
class TournamentNotificationVariables(
    private val actorNameResolver: ActorNameResolver,
    private val tournamentRepository: TournamentRepository,
    private val tournamentUserRepository: TournamentUserRepository,
) {
    fun context(
        tournamentId: Long,
        actorId: UUID,
    ): ActorContext {
        // 프사·fallback 닉네임(프로필)은 여기서, actorName 은 토너먼트 전용 닉네임 우선으로 덮는다(#1018).
        val actor = actorNameResolver.resolveAttributes(actorId)
        val actorName = tournamentNicknameOrNull(tournamentId, actorId) ?: actor.name
        // 토너먼트가 삭제·불일치로 없으면(best-effort) fallback — 변수 하나 때문에 알림 전체를 떨구지 않는다.
        val tournamentName = tournamentRepository.findTournamentById(tournamentId)?.name ?: FALLBACK_NAME
        return ActorContext(
            variables =
                mapOf(
                    "actorName" to actorName,
                    "tournamentId" to tournamentId.toString(),
                    "tournamentName" to tournamentName,
                ),
            imageUrl = actor.profileImage,
        )
    }

    // 알림 문구에 쓸 토너먼트 전용 닉네임(#1018). 참여자 표시명은 유저 프로필이 아니라 토너먼트에서 정한 이름을 쓴다.
    // 루트 참여자(주최자·멤버·게스트 join)는 루트 TU 에서, 플레이링크 게스트는 루트의 클론 중 자기 소유 클론 TU 에서 푼다.
    // TU 를 못 찾거나 닉네임이 NULL(레거시)이면 null 을 돌려 호출부가 프로필 닉네임으로 폴백하게 한다.
    private fun tournamentNicknameOrNull(
        tournamentId: Long,
        actorId: UUID,
    ): String? {
        tournamentUserRepository.findByTournamentIdAndUserId(tournamentId, actorId)?.let { return it.nickname }
        val clones = tournamentRepository.findBySourceTournamentId(tournamentId)
        if (clones.isEmpty()) return null
        return tournamentUserRepository
            .findByIds(clones.map { it.ownerTournamentUserId }.toSet())
            .firstOrNull { it.userId == actorId }
            ?.nickname
    }

    companion object {
        private const val FALLBACK_NAME = "토너먼트"
    }
}
