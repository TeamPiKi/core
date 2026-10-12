package com.depromeet.piki.notification.handler

import com.depromeet.piki.item.event.ItemParsingIncomplete
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.notification.domain.NotificationType
import org.springframework.stereotype.Component
import java.util.UUID

// 일부만 채운 파싱(#944). 등록·새로고침으로 갈리지 않는다
@Component
class ItemParsingIncompleteHandler(
    private val recipientResolver: ItemParsingRecipientResolver,
    private val itemSnapshotRepository: ItemSnapshotRepository,
) : NotificationEventHandler<ItemParsingIncomplete>(NotificationType.ITEM_PARSING_INCOMPLETE) {
    override fun resolveRefId(event: ItemParsingIncomplete): Long = event.itemId

    override fun resolveRecipients(event: ItemParsingIncomplete): Set<UUID> = setOf(event.owner)

    override fun resolveActorContext(event: ItemParsingIncomplete): ActorContext =
        ActorContext(variables = mapOf("itemName" to ItemDisplayName.of(itemSnapshotRepository.findById(event.snapshotId)?.name)))

    override fun resolveRouting(event: ItemParsingIncomplete) = recipientResolver.routingOf(event.slot)
}
