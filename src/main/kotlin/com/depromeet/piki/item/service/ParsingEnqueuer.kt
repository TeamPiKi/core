package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.repository.ItemParseOutboxRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class ParsingEnqueuer(
    private val itemSnapshotRepository: ItemSnapshotRepository,
    private val itemParseOutboxRepository: ItemParseOutboxRepository,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun enqueue(
        itemId: Long,
        requestedBy: UUID,
    ): ItemSnapshot {
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(itemId, requestedBy))
        itemParseOutboxRepository.save(ItemParseOutbox(itemSnapshotId = snapshot.getId()))
        return snapshot
    }
}
