package com.depromeet.piki.notification.handler

import com.depromeet.piki.item.event.ItemParsingCompleted
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.notification.domain.NotificationRouting
import com.depromeet.piki.notification.domain.NotificationType
import org.springframework.stereotype.Component
import java.util.UUID

// 아이템 파싱 완료 알림. 수신자는 snapshotId(버전)를 역조회해 위시 주인 ∪ 토너먼트 참가자로 모은다
// (ItemParsingRecipientResolver, 실패 알림과 공유).
//
// 문구는 title=아이템 이름 / body=상태 로 나뉜다(#913). OS 푸시 제목은 줄바꿈 없이 뒤가 잘려서, 이름과 상태를 한
// 줄에 담으면 이름이 길 때 정작 무슨 일인지가 사라진다. 이름만 제목에 두면 잘려도 잃는 게 없고, body 는 두 줄까지
// 보이므로 상태 문구가 온전히 남는다. 그래서 이름은 표시 글자 절단을 하지 않는다(char 안전망만).
//
// body 문구는 출처(위시/토너먼트)와 무관하게 하나다. 출처별로 가르려면 수신자마다 달라져야 하는데, dispatcher 는
// 라우팅·문구를 수신자 루프 **밖에서 한 번** 해석해 전원에게 같은 값을 박는다. 한 snapshot 에 위시 주인과
// 토너먼트 등록자가 함께 붙을 수 있어(공유 #825 의 "진행 중 합류"), 그 상태로 가르면 위시 주인이 토너먼트 문구를
// 받는다. 출처별 문구는 수신자별 라우팅 해석과 함께 후속(#933)에서 다룬다.
@Component
class ItemParsingCompletedHandler(
    private val recipientResolver: ItemParsingRecipientResolver,
    private val itemSnapshotRepository: ItemSnapshotRepository,
) : NotificationEventHandler<ItemParsingCompleted>(NotificationType.ITEM_PARSING_COMPLETED) {
    override fun resolveRefId(event: ItemParsingCompleted): Long = event.itemId

    override fun resolveRecipients(event: ItemParsingCompleted): Set<UUID> = recipientResolver.resolve(event.snapshotId)

    override fun resolveRouting(event: ItemParsingCompleted): NotificationRouting = recipientResolver.resolveRouting(event.snapshotId)

    // 문구 변수는 itemName(제목) 하나다. body 는 변수 없는 고정 문구라 템플릿이 통째로 소유한다 —
    // 백오피스(#252)에서 title·body 둘 다 그대로 편집된다.
    // best-effort: 버전이 없거나 이름이 비어도 이름 하나 때문에 알림 전체를 떨구지 않고 기본값을 쓴다
    // (토너먼트 알림의 tournamentName fallback 과 같은 결).
    override fun resolveActorContext(event: ItemParsingCompleted): ActorContext =
        ActorContext(variables = mapOf("itemName" to ItemDisplayName.of(itemSnapshotRepository.findById(event.snapshotId)?.name)))
}
