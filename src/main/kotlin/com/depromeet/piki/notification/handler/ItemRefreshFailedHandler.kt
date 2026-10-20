package com.depromeet.piki.notification.handler

import com.depromeet.piki.item.event.ItemParsingFailed
import com.depromeet.piki.notification.domain.NotificationType
import org.springframework.stereotype.Component
import java.util.UUID

// 새로고침 실패(#1036). 기존 값은 그대로라 카드 상태는 바뀌지 않는다
@Component
class ItemRefreshFailedHandler(
    private val recipientResolver: ItemParsingRecipientResolver,
) : NotificationEventHandler<ItemParsingFailed>(NotificationType.ITEM_REFRESH_FAILED) {
    override fun resolveRefId(event: ItemParsingFailed): Long = event.slot.id

    override fun resolveRecipients(event: ItemParsingFailed): Set<UUID> = if (event.refresh) setOf(event.owner) else emptySet()

    override fun resolveRouting(event: ItemParsingFailed) = recipientResolver.routingOf(event.slot)
}
