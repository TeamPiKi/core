package com.depromeet.piki.notification.handler

import com.depromeet.piki.item.event.ItemParsingIncomplete
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.notification.domain.NotificationRouting
import com.depromeet.piki.notification.domain.NotificationType
import org.springframework.stereotype.Component
import java.util.UUID

// 아이템 파싱이 일부 필드만 채우고 끝났음을 알린다(#944). 수신자·라우팅 규칙은 완료·실패 알림과 동일하다
// (ItemParsingRecipientResolver 공유 — 위시 주인 ∪ 토너먼트 참가자).
//
// 문구 변수는 완료 알림과 같이 itemName(제목) 하나이고 body 는 고정이다. 다만 이 알림은 이름조차 못 얻은 버전에도
// 나갈 수 있다 — 가격·이미지만 건지고 이름을 놓친 경우다. 그때는 ItemDisplayName 의 기본값이 제목을 메운다.
//
// 라우팅은 수신자별로 갈린다(#933) — 각 수신자가 자기 위시(wishId)/자기 토너먼트 딥링크를 받는다. body 문구는
// 출처로 가르지 않아(단일 문구) 라우팅만 수신자별로 채운다.
@Component
class ItemParsingIncompleteHandler(
    private val recipientResolver: ItemParsingRecipientResolver,
    private val itemSnapshotRepository: ItemSnapshotRepository,
) : NotificationEventHandler<ItemParsingIncomplete>(NotificationType.ITEM_PARSING_INCOMPLETE) {
    override fun resolveRefId(event: ItemParsingIncomplete): Long = event.itemId

    override fun resolveRecipients(event: ItemParsingIncomplete): Set<UUID> = recipientResolver.resolve(event.snapshotId)

    override fun resolveActorContext(event: ItemParsingIncomplete): ActorContext =
        ActorContext(variables = mapOf("itemName" to ItemDisplayName.of(itemSnapshotRepository.findById(event.snapshotId)?.name)))

    override fun resolveRecipientContexts(
        event: ItemParsingIncomplete,
        recipients: Set<UUID>,
    ): Map<UUID, RecipientContext> {
        val routings = recipientResolver.resolveRoutingsBySnapshot(event.snapshotId)
        return recipients.associateWith { userId ->
            RecipientContext(routing = routings[userId] ?: NotificationRouting.Wish(null))
        }
    }
}
