package com.depromeet.piki.notification.handler

import com.depromeet.piki.item.event.ItemParsingFailed
import com.depromeet.piki.notification.domain.NotificationType
import org.springframework.stereotype.Component
import java.util.UUID

// 등록 파싱 실패. 새로고침 실패는 ITEM_REFRESH_FAILED(#1036)로 따로 간다. 실패하면 이름이 없어 기본 이름을 싣는다
@Component
class ItemParsingFailedHandler(
    private val recipientResolver: ItemParsingRecipientResolver,
) : NotificationEventHandler<ItemParsingFailed>(NotificationType.ITEM_PARSING_FAILED) {
    override fun resolveRefId(event: ItemParsingFailed): Long = event.slot.id

    override fun resolveRecipients(event: ItemParsingFailed): Set<UUID> = if (event.refresh) emptySet() else setOf(event.owner)

    override fun resolveActorContext(event: ItemParsingFailed): ActorContext =
        ActorContext(variables = mapOf("itemName" to ItemDisplayName.of(null)))

    override fun resolveRouting(event: ItemParsingFailed) = recipientResolver.routingOf(event.slot)
}
