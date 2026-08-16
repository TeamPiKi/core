package com.depromeet.piki.notification.handler

import com.depromeet.piki.item.event.ItemParsingFailed
import com.depromeet.piki.notification.domain.NotificationRouting
import com.depromeet.piki.notification.domain.NotificationType
import org.springframework.stereotype.Component
import java.util.UUID

// 아이템 파싱 실패 알림. 수신자 규칙은 완료 알림과 동일 — snapshotId 역조회로 위시 주인 ∪ 토너먼트 참가자.
// (ItemParsingRecipientResolver 를 완료·미완 핸들러와 공유해 동일 로직 중복을 없앤다.)
//
// 라우팅은 수신자별로 갈린다(#933) — 각 수신자가 자기 위시(wishId)/자기 토너먼트 딥링크를 받는다. 다만 body 문구는
// 완료와 달리 출처로 가르지 않는다 — 실패한 버전은 이름이 비어(추출 자체 실패) 출처별 문구를 만들 근거가 약하고,
// 단일 문구로 충분하다. 그래서 문구는 그대로 템플릿이 소유하고 여기선 라우팅만 수신자별로 채운다.
@Component
class ItemParsingFailedHandler(
    private val recipientResolver: ItemParsingRecipientResolver,
) : NotificationEventHandler<ItemParsingFailed>(NotificationType.ITEM_PARSING_FAILED) {
    override fun resolveRefId(event: ItemParsingFailed): Long = event.itemId

    override fun resolveRecipients(event: ItemParsingFailed): Set<UUID> = recipientResolver.resolve(event.snapshotId)

    override fun resolveRecipientContexts(
        event: ItemParsingFailed,
        recipients: Set<UUID>,
    ): Map<UUID, RecipientContext> {
        val routings = recipientResolver.resolveRoutingsBySnapshot(event.snapshotId)
        // 방어: 수신자인데 좌표를 못 찾으면 위시 폴백(wishId 없이도 알림은 나간다).
        return recipients.associateWith { userId ->
            RecipientContext(routing = routings[userId] ?: NotificationRouting.Wish(null))
        }
    }
}
