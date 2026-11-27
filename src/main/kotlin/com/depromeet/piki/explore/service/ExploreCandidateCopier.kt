package com.depromeet.piki.explore.service

import com.depromeet.piki.explore.domain.ExploreException
import com.depromeet.piki.explore.service.dto.CopyExploreCandidateCommand
import com.depromeet.piki.explore.service.dto.ExploreWishCopyResult
import com.depromeet.piki.explore.service.dto.GetExplorePickQuery
import com.depromeet.piki.item.service.UniqueConflictRetry
import org.springframework.stereotype.Service

@Service
class ExploreCandidateCopier(
    private val explorePickReader: ExplorePickReader,
    private val exploreWishCopyWriter: ExploreWishCopyWriter,
) {
    fun copy(command: CopyExploreCandidateCommand): ExploreWishCopyResult {
        val candidates = explorePickReader.getCandidates(GetExplorePickQuery(viewerId = command.userId, pickId = command.pickId))
        val candidate =
            candidates.candidates.firstOrNull { it.candidateId == command.candidateId } ?: throw ExploreException.pickNotFound()
        return UniqueConflictRetry.once("explore.copy") { exploreWishCopyWriter.findOrCreate(command.userId, candidate.item.itemId) }
    }
}
