package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ParseRequest
import com.depromeet.piki.item.domain.ParseTrigger
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.item.repository.ParseRequestRepository
import org.springframework.stereotype.Component
import java.util.UUID

// 작업 큐 적재의 단일 지점(#1073). @Transactional 이 없는 이유: 적재는 등록·새로고침 쓰기 묶음의 일부라 호출부 경계에 합류한다.
@Component
class ParsingEnqueuer(
    private val itemSnapshotRepository: ItemSnapshotRepository,
    private val parseRequestRepository: ParseRequestRepository,
) {
    fun enqueue(
        itemId: Long,
        requestedBy: UUID,
        triggerType: ParseTrigger,
    ): ItemSnapshot {
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(itemId, requestedBy))
        parseRequestRepository.save(
            ParseRequest(
                itemId = itemId,
                requestedBy = requestedBy,
                triggerType = triggerType,
                resultSnapshotId = snapshot.getId(),
            ),
        )
        return snapshot
    }
}
