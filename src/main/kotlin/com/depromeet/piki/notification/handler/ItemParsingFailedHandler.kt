package com.depromeet.piki.notification.handler

import com.depromeet.piki.item.event.ItemParsingFailed
import com.depromeet.piki.notification.domain.NotificationRouting
import com.depromeet.piki.notification.domain.NotificationType
import org.springframework.stereotype.Component
import java.util.UUID

// 아이템 파싱 실패 알림. 수신자 규칙은 완료 알림과 동일 — itemId 역조회로 위시 주인 ∪ 토너먼트 참가자.
// (ItemParsingRecipientResolver 를 완료 핸들러와 공유해 동일 로직 중복을 없앤다.)
@Component
class ItemParsingFailedHandler(
    private val recipientResolver: ItemParsingRecipientResolver,
) : NotificationEventHandler<ItemParsingFailed>(NotificationType.ITEM_PARSING_FAILED) {
    override fun resolveRefId(event: ItemParsingFailed): Long = event.itemId

    override fun resolveRecipients(event: ItemParsingFailed): Set<UUID> = recipientResolver.resolve(event.snapshotId)

    override fun resolveRouting(event: ItemParsingFailed): NotificationRouting = recipientResolver.resolveRouting(event.snapshotId)
}
