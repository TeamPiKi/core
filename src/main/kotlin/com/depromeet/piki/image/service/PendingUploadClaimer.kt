package com.depromeet.piki.image.service

import com.depromeet.piki.image.domain.PendingUploadContext
import com.depromeet.piki.image.repository.PendingUploadRepository
import org.springframework.stereotype.Component
import java.util.UUID

// 삭제가 곧 claim 이다 - confirm 과 폴링이 같은 key 를 다퉈도 삭제에 성공한 한쪽만 가져간다.
// 트랜잭션은 호출부가 연다(REQUIRED). 자기 트랜잭션을 열면 claim 이 등록과 따로 커밋돼 멱등이 깨진다.
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
