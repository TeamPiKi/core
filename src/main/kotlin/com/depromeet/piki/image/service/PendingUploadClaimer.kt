package com.depromeet.piki.image.service

import com.depromeet.piki.image.domain.PendingUploadContext
import com.depromeet.piki.image.repository.PendingUploadRepository
import org.springframework.stereotype.Component
import java.util.UUID

// confirm 과 폴링이 공유하는 claim 프리미티브 — 주어진 key 중 (context, user, tournament) 맥락이 일치하는 pending 을
// FOR UPDATE 로 잠가 삭제(claim)하고, claim 한 key 를 돌려준다. 호출부(위시·토너먼트 persistence 의 registerClaimedImages)의
// @Transactional 안에서 실행된다(REQUIRED 전파) — confirm·폴링이 같은 key 를 다퉈도 삭제에 성공한 한쪽만 claim 한다(멱등).
// 다른 user·토너먼트·context 의 매핑은 걸러내, 남의 key 나 잘못된 맥락으로 등록되지 않게 한다.
@Component
class PendingUploadClaimer(
    private val pendingUploadRepository: PendingUploadRepository,
) {
    fun claim(
        imageKeys: List<String>,
        context: PendingUploadContext,
        userId: UUID,
        tournamentId: Long?,
    ): List<String> {
        val claimed =
            pendingUploadRepository
                .findAllByImageKeysForUpdate(imageKeys)
                .filter { it.context == context && it.userId == userId && it.tournamentId == tournamentId }
        if (claimed.isEmpty()) return emptyList()
        pendingUploadRepository.deleteAll(claimed)
        return claimed.map { it.imageKey }
    }
}
