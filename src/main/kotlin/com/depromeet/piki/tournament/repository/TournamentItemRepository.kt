package com.depromeet.piki.tournament.repository

import com.depromeet.piki.image.domain.ImageKey
import com.depromeet.piki.item.domain.CanonicalInput
import com.depromeet.piki.item.domain.NormalizedInput
import com.depromeet.piki.tournament.domain.TournamentItem
import java.util.UUID

interface TournamentItemRepository {
    fun save(item: TournamentItem): TournamentItem

    fun saveAll(items: List<TournamentItem>): List<TournamentItem>

    fun countByTournamentId(tournamentId: Long): Int

    fun findIdsByTournamentId(tournamentId: Long): List<Long>

    // 수기 수정 직렬화용 행 락 조회
    fun findByIdForUpdate(id: Long): TournamentItem?

    fun findByIdIncludingDeletedForUpdate(id: Long): TournamentItem?

    fun findByIdIncludingDeleted(id: Long): TournamentItem?

    fun findIdByTournamentIdAndNormalizedInput(
        tournamentId: Long,
        normalized: NormalizedInput,
    ): Long?

    // 이 토너먼트에 이미 요청된 이미지 키
    fun findRequestedImageKeys(
        tournamentId: Long,
        keys: Collection<ImageKey>,
    ): Set<ImageKey>

    // 이 토너먼트의 출전 중 정체 값이 같은 아이템을 담은 것
    fun findIdByTournamentIdAndItemCanonical(
        tournamentId: Long,
        canonical: CanonicalInput,
    ): Long?

    // 이 상품을 대기실(PENDING) 토너먼트에 출전시킨 카드 — 해소 통지 수신자 후보(#1028·#1051).
    fun findPendingCardsByItemId(itemId: Long): List<TournamentItemCardView>

    fun findAllByTournamentId(tournamentId: Long): List<TournamentItem>

    fun findAllByTournamentIds(ids: List<Long>): List<TournamentItem>

    fun findByIds(ids: List<Long>): List<TournamentItem>

    fun findById(id: Long): TournamentItem?

    fun softDeleteIfPending(
        id: Long,
        tournamentId: Long,
    ): Int

    fun softDeleteAllByTournamentId(tournamentId: Long)

    // 담은 사람을 옮긴다(#1081). 유니크 키가 (tournament_id, item_id)라 user_id 는 키에 없어 충돌하지 않는다.
    fun transferToUser(
        fromUserId: UUID,
        toUserId: UUID,
        tournamentIds: List<Long>,
    ): Int
}
