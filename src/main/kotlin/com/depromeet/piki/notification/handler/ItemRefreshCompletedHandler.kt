package com.depromeet.piki.notification.handler

import com.depromeet.piki.item.event.ItemParsingCompleted
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.notification.domain.NotificationType
import org.springframework.stereotype.Component
import java.util.UUID

// 새로고침(재추출) 성공(#1036). 등록 완료 알림과 수신자가 배타적이다
@Component
class ItemRefreshCompletedHandler(
    private val recipientResolver: ItemParsingRecipientResolver,
    private val itemSnapshotRepository: ItemSnapshotRepository,
) : NotificationEventHandler<ItemParsingCompleted>(NotificationType.ITEM_REFRESH_COMPLETED) {
    override fun resolveRefId(event: ItemParsingCompleted): Long = event.itemId

    override fun resolveRecipients(event: ItemParsingCompleted): Set<UUID> = if (event.refresh) setOf(event.owner) else emptySet()

    override fun resolveActorContext(event: ItemParsingCompleted): ActorContext =
        ActorContext(variables = mapOf("itemName" to ItemDisplayName.of(itemSnapshotRepository.findById(event.snapshotId)?.name)))

    override fun resolveRouting(event: ItemParsingCompleted) = recipientResolver.routingOf(event.slot)
}
