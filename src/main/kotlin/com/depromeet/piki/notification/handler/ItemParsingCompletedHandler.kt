package com.depromeet.piki.notification.handler

import com.depromeet.piki.item.event.ItemParsingCompleted
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.notification.domain.NotificationRouting
import com.depromeet.piki.notification.domain.NotificationType
import org.springframework.stereotype.Component
import java.util.UUID

// 등록 파싱 완료. 새로고침 완료는 ITEM_REFRESH_COMPLETED(#1036)로 따로 간다
// title=이름 / body=상태로 나눈다(#913). OS 푸시 제목은 뒤가 잘려 한 줄에 담으면 상태가 사라진다
@Component
class ItemParsingCompletedHandler(
    private val recipientResolver: ItemParsingRecipientResolver,
    private val itemSnapshotRepository: ItemSnapshotRepository,
) : NotificationEventHandler<ItemParsingCompleted>(NotificationType.ITEM_PARSING_COMPLETED) {
    override fun resolveRefId(event: ItemParsingCompleted): Long = event.itemId

    override fun resolveRecipients(event: ItemParsingCompleted): Set<UUID> = if (event.refresh) emptySet() else setOf(event.owner)

    override fun resolveActorContext(event: ItemParsingCompleted): ActorContext =
        ActorContext(variables = mapOf("itemName" to ItemDisplayName.of(itemSnapshotRepository.findById(event.snapshotId)?.name)))

    // body 문구는 출처(위시/토너먼트)로 갈린다(#933)
    override fun resolveRecipientContexts(
        event: ItemParsingCompleted,
        recipients: Set<UUID>,
    ): Map<UUID, RecipientContext> {
        val routing = recipientResolver.routingOf(event.slot)
        return recipients.associateWith {
            RecipientContext(routing = routing, variables = mapOf("completionMessage" to completionMessageOf(routing)))
        }
    }

    private fun completionMessageOf(routing: NotificationRouting): String =
        when (routing) {
            is NotificationRouting.Wish -> WISH_COMPLETION_MESSAGE
            is NotificationRouting.Tournament -> TOURNAMENT_COMPLETION_MESSAGE
        }

    companion object {
        // 출처별로 갈라야 하는데 템플릿 테이블은 타입당 한 행이라 코드가 소유한다. 백오피스(#252) 편집 대상이 아니다
        const val WISH_COMPLETION_MESSAGE = "위시 저장이 성공했어요"
        const val TOURNAMENT_COMPLETION_MESSAGE = "토너먼트 후보가 등록됐어요"
    }
}
