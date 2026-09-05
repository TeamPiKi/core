package com.depromeet.piki.notification.handler

import com.depromeet.piki.item.event.ItemParsingFailed
import com.depromeet.piki.notification.domain.NotificationRouting
import com.depromeet.piki.notification.domain.NotificationType
import org.springframework.stereotype.Component
import java.util.UUID

// 위시 **새로고침**(재추출)이 실패했음을 알린다(#1036). 수신자·배타성 규칙은 새로고침 완료와 같다
// (ItemRefreshCompletedHandler 참고) — 그 버전으로 새로고침한 위시 주인만 받고, 등록 실패 알림은 그들을 뺀다.
//
// 등록 실패와 문구가 갈려야 하는 이유: 새로고침은 성공(READY) 항목에서만 시작되므로 실패해도 카드는 표시값 파생(#858)으로
// 옛 성공본을 그대로 보인다. "가져오지 못했어요" 만 받으면 사용자는 정보가 사라졌다고 오해한다.
//
// 템플릿 변수를 채우지 않는다 — 실패한 버전은 이름이 비어(추출 자체 실패) itemName 이 늘 기본값이라 쓸모가 없고,
// 문구는 title·body 모두 고정이다. 그래서 스냅샷 원본 저장소도 필요 없다.
@Component
class ItemRefreshFailedHandler(
    private val recipientResolver: ItemParsingRecipientResolver,
) : NotificationEventHandler<ItemParsingFailed>(NotificationType.ITEM_REFRESH_FAILED) {
    override fun resolveRefId(event: ItemParsingFailed): Long = event.itemId

    override fun resolveRecipients(event: ItemParsingFailed): Set<UUID> =
        recipientResolver.resolveRefreshContexts(event.snapshotId).keys

    override fun resolveRecipientContexts(
        event: ItemParsingFailed,
        recipients: Set<UUID>,
    ): Map<UUID, RecipientContext> {
        val contexts = recipientResolver.resolveRefreshContexts(event.snapshotId)
        return recipients.associateWith { userId -> contexts[userId] ?: RecipientContext(routing = NotificationRouting.Wish(null)) }
    }
}
