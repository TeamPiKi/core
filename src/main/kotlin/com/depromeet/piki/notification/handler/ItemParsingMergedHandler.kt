package com.depromeet.piki.notification.handler

import com.depromeet.piki.item.event.ItemSlotMerged
import com.depromeet.piki.item.service.ItemCardService
import com.depromeet.piki.notification.domain.NotificationRouting
import com.depromeet.piki.notification.domain.NotificationType
import org.springframework.stereotype.Component
import java.util.UUID

// 사라진 카드로 가면 404 라 남은 카드 좌표로 보낸다
@Component
class ItemParsingMergedHandler(
    private val recipientResolver: ItemParsingRecipientResolver,
    private val itemCardService: ItemCardService,
) : NotificationEventHandler<ItemSlotMerged>(NotificationType.ITEM_PARSING_MERGED) {
    override fun resolveRefId(event: ItemSlotMerged): Long = event.itemId

    override fun resolveRecipients(event: ItemSlotMerged): Set<UUID> = setOf(event.owner)

    // 눌러서 가는 남은 카드와 같은 이름이 보이게 그 카드 주인의 맥락으로 고른다
    override fun resolveActorContext(event: ItemSlotMerged): ActorContext =
        ActorContext(variables = mapOf("itemName" to ItemDisplayName.of(itemCardService.versionsOf(event.itemId).displayFor(event.holderOwner).name)))

    override fun resolveRecipientContexts(
        event: ItemSlotMerged,
        recipients: Set<UUID>,
    ): Map<UUID, RecipientContext> {
        val routing = recipientResolver.routingOf(event.holder)
        return recipients.associateWith {
            RecipientContext(routing = routing, variables = mapOf("mergedMessage" to mergedMessageOf(routing)))
        }
    }

    private fun mergedMessageOf(routing: NotificationRouting): String =
        when (routing) {
            is NotificationRouting.Wish -> WISH_MERGED_MESSAGE
            is NotificationRouting.Tournament -> TOURNAMENT_MERGED_MESSAGE
        }

    companion object {
        // 출처별로 갈라야 하는데 템플릿 테이블은 타입당 한 행이라 코드가 소유한다
        const val WISH_MERGED_MESSAGE = "기존 위시에 합쳐졌어요"
        const val TOURNAMENT_MERGED_MESSAGE = "기존 토너먼트 후보에 합쳐졌어요"
    }
}
