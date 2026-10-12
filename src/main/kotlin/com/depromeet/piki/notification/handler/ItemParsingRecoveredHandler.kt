package com.depromeet.piki.notification.handler

import com.depromeet.piki.item.event.ItemParsingCompleted
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.notification.domain.NotificationRouting
import com.depromeet.piki.notification.domain.NotificationType
import org.springframework.stereotype.Component
import java.util.UUID

// 미완으로 멈춰 있던 카드가 다른 사람의 성공 파싱으로 채워졌음을 알린다(#1028). 받아 둔 미완 알림은 발송 후 불변이라
// 낡은 알림을 고치는 대신 해소 사실을 새로 알린다
@Component
class ItemParsingRecoveredHandler(
    private val recipientResolver: ItemParsingRecipientResolver,
    private val itemSnapshotRepository: ItemSnapshotRepository,
) : NotificationEventHandler<ItemParsingCompleted>(NotificationType.ITEM_PARSING_RECOVERED) {
    override fun resolveRefId(event: ItemParsingCompleted): Long = event.itemId

    override fun resolveRecipients(event: ItemParsingCompleted): Set<UUID> = routingsOf(event).keys

    override fun resolveActorContext(event: ItemParsingCompleted): ActorContext =
        ActorContext(variables = mapOf("itemName" to ItemDisplayName.of(itemSnapshotRepository.findById(event.snapshotId)?.name)))

    override fun resolveRecipientContexts(
        event: ItemParsingCompleted,
        recipients: Set<UUID>,
    ): Map<UUID, RecipientContext> {
        val routings = routingsOf(event)
        return recipients.associateWith { RecipientContext(routing = routings[it] ?: NotificationRouting.Wish(null)) }
    }

    private fun routingsOf(event: ItemParsingCompleted): Map<UUID, NotificationRouting> =
        recipientResolver.resolveRecoveredRoutings(event.itemId, event.snapshotId, event.slot)
}
