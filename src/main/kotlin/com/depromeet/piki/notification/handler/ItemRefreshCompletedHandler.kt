package com.depromeet.piki.notification.handler

import com.depromeet.piki.item.event.ItemParsingCompleted
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.notification.domain.NotificationRouting
import com.depromeet.piki.notification.domain.NotificationType
import org.springframework.stereotype.Component
import java.util.UUID

// 위시 **새로고침**(재추출)이 성공했음을 알린다(#1036).
//
// 파싱 완료라는 한 사실(ItemParsingCompleted)에서 등록 완료 알림과 갈라진다. 새로고침은 등록과 같은 PENDING 버전을
// 만들어 같은 이벤트로 끝나는데, 새로고침한 사람이 "위시 저장이 성공했어요" 를 받으면 어긋난다. 클라가 알림 종류로
// 등록/갱신을 구분해야 해 타입을 따로 둔다(템플릿은 타입당 하나).
//
// 수신자는 등록 완료 알림과 **배타적**이다 — 그 버전으로 새로고침한 위시 주인만 받고(ItemParsingRecipientResolver
// .resolveRefreshRoutings), 등록 완료 알림은 그들을 뺀다(resolveRegistered). 갈리는 근거는 위시 생성시각이 버전보다
// 앞서는가 하나뿐이라 등록/새로고침을 적는 컬럼이 없다. 토너먼트 출전은 새로고침이 없어 여기 오지 않는다.
@Component
class ItemRefreshCompletedHandler(
    private val recipientResolver: ItemParsingRecipientResolver,
    private val itemSnapshotRepository: ItemSnapshotRepository,
) : NotificationEventHandler<ItemParsingCompleted>(NotificationType.ITEM_REFRESH_COMPLETED) {
    override fun resolveRefId(event: ItemParsingCompleted): Long = event.itemId

    // 아무도 새로고침하지 않은 버전이면 빈 집합이고 dispatch 가 거기서 끝난다 — 등록 파싱 대다수가 이 경우다.
    override fun resolveRecipients(event: ItemParsingCompleted): Set<UUID> =
        recipientResolver.resolveRefreshRoutings(event.snapshotId).keys

    // title 의 itemName 은 방금 성공한 새 버전의 이름 — 수신자가 새로고침된 카드에서 보게 되는 그 값이다.
    override fun resolveActorContext(event: ItemParsingCompleted): ActorContext {
        val name = itemSnapshotRepository.findById(event.snapshotId)?.name
        return ActorContext(variables = mapOf("itemName" to ItemDisplayName.of(name)))
    }

    // 라우팅은 수신자별 자기 위시(wishId). 수신자 도출과 같은 조회 1회라 수신자 수와 무관하다.
    override fun resolveRecipientContexts(
        event: ItemParsingCompleted,
        recipients: Set<UUID>,
    ): Map<UUID, RecipientContext> {
        val routings = recipientResolver.resolveRefreshRoutings(event.snapshotId)
        // 방어: 수신자인데 좌표를 못 찾으면(그 사이 삭제 등) wishId 없이도 알림은 나간다.
        return recipients.associateWith { userId ->
            RecipientContext(routing = routings[userId] ?: NotificationRouting.Wish(null))
        }
    }
}
