package com.depromeet.piki.notification.handler

import com.depromeet.piki.item.event.ItemParsingCompleted
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.notification.domain.NotificationRouting
import com.depromeet.piki.notification.domain.NotificationType
import org.springframework.stereotype.Component
import java.util.UUID

// 실패·미완으로 멈춰 있던 카드가 **다른 사람의 성공 파싱**으로 채워졌음을 알린다(#1028).
//
// 파싱 완료라는 한 사실(ItemParsingCompleted)에서 완료 알림과 함께 갈라져 나온다. 같은 링크는 한 item 을 공유하므로
// (#825) 남이 성공시키면 표시값 파생(#858)이 멈춰 있던 사람의 카드까지 채운다 — 그런데 그 사람이 받아 둔 실패 알림은
// 발송 후 불변이라 낡은 채로 남는다. 낡은 알림을 고치는 대신 해소 사실을 새로 알리는 것이 이 알림이다.
//
// 수신자는 완료 알림과 **배타적**이다 — 그 버전을 기다린 사람은 완료 알림, 다른 미완성 버전에 멈춰 있던 사람은 이 알림.
// 갈리는 근거는 포인터 위치 하나뿐이라 "남 때문에" 를 판정하는 플래그가 없다(ItemParsingRecipientResolver 참고).
//
// 포인터는 건드리지 않는다. 남의 파싱 완료 핸들러가 다른 사용자의 wish 행을 쓰면 그 사람의 새로고침·수정과 락 경합이
// 나는데, 표시값 파생이 이미 카드를 채워 주고 담기 판정·수정 base·새로고침 판정도 전부 표시값을 보므로(#1006·#1007)
// 포인터가 FAILED 로 남아 있어도 어디에도 걸리지 않는다.
@Component
class ItemParsingRecoveredHandler(
    private val recipientResolver: ItemParsingRecipientResolver,
    private val itemSnapshotRepository: ItemSnapshotRepository,
) : NotificationEventHandler<ItemParsingCompleted>(NotificationType.ITEM_PARSING_RECOVERED) {
    override fun resolveRefId(event: ItemParsingCompleted): Long = event.itemId

    // 아무도 안 멈춰 있으면 빈 집합이고 dispatch 가 거기서 끝난다 — 대다수 파싱 완료가 이 경우다.
    override fun resolveRecipients(event: ItemParsingCompleted): Set<UUID> =
        recipientResolver.resolveRecoveredRoutingsByItem(event.itemId).keys

    // title 의 itemName 은 **방금 성공한 버전** 의 이름이다 — 수신자가 지금 카드에서 보게 되는 그 값.
    override fun resolveActorContext(event: ItemParsingCompleted): ActorContext {
        val name = itemSnapshotRepository.findById(event.snapshotId)?.name
        return ActorContext(variables = mapOf("itemName" to ItemDisplayName.of(name)))
    }

    // 라우팅은 수신자별로 갈린다(#933) — 각자 자기 위시(wishId)/자기 토너먼트 좌표로 간다. 조회는 수신자 도출과
    // 같은 2회짜리 배치를 한 번 더 도는 것이라 수신자 수와 무관하다(다른 파싱 핸들러와 같은 비용 구조).
    override fun resolveRecipientContexts(
        event: ItemParsingCompleted,
        recipients: Set<UUID>,
    ): Map<UUID, RecipientContext> {
        val routings = recipientResolver.resolveRecoveredRoutingsByItem(event.itemId)
        // 방어: 수신자인데 좌표를 못 찾으면(그 사이 삭제 등) 위시 폴백 — wishId 없이도 알림은 나간다.
        return recipients.associateWith { userId ->
            RecipientContext(routing = routings[userId] ?: NotificationRouting.Wish(null))
        }
    }
}
