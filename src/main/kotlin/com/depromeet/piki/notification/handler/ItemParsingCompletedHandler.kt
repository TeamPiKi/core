package com.depromeet.piki.notification.handler

import com.depromeet.piki.item.event.ItemParsingCompleted
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.notification.domain.NotificationKind
import com.depromeet.piki.notification.domain.NotificationRouting
import com.depromeet.piki.notification.domain.NotificationType
import org.springframework.stereotype.Component
import java.util.UUID

// 아이템 파싱 완료 알림. 수신자는 snapshotId(버전)를 역조회해 위시 주인 ∪ 토너먼트 참가자로 모은다
// (ItemParsingRecipientResolver, 실패·미완 알림과 공유).
//
// 문구는 title=아이템 이름 / body=상태 로 나뉜다(#913). OS 푸시 제목은 줄바꿈 없이 뒤가 잘려서, 이름과 상태를 한
// 줄에 담으면 이름이 길 때 정작 무슨 일인지가 사라진다. 이름만 제목에 두면 잘려도 잃는 게 없고, body 는 두 줄까지
// 보이므로 상태 문구가 온전히 남는다.
//
// body 문구는 등록 출처(위시/토너먼트)로 갈린다(#933) — 위시 "위시 저장이 성공했어요" / 토너먼트 "아이템이 등록됐어요".
// 한 snapshot 에 위시 주인·토너먼트 등록자가 함께 붙을 수 있어(공유 #825 의 "진행 중 합류") 수신자마다 딥링크·문구가
// 달라야 한다. dispatcher 가 수신자별 컨텍스트(resolveRecipientContexts)를 받아 각 수신자용으로 렌더한다.
// (#924 가 출처별 문구를 채택하려다 "수신자 루프 밖 1회 해석"이라 위시 주인이 토너먼트 문구를 받아 되돌렸던 것을,
//  수신자별 해석이 생겨 되살린다.)
@Component
class ItemParsingCompletedHandler(
    private val recipientResolver: ItemParsingRecipientResolver,
    private val itemSnapshotRepository: ItemSnapshotRepository,
) : NotificationEventHandler<ItemParsingCompleted>(NotificationType.ITEM_PARSING_COMPLETED) {
    override fun resolveRefId(event: ItemParsingCompleted): Long = event.itemId

    override fun resolveRecipients(event: ItemParsingCompleted): Set<UUID> = recipientResolver.resolve(event.snapshotId)

    // title 변수 itemName 은 전 수신자 공유 — 같은 아이템이라 동일하다. (버전이 없거나 이름이 비면 기본값, best-effort)
    override fun resolveActorContext(event: ItemParsingCompleted): ActorContext =
        ActorContext(variables = mapOf("itemName" to ItemDisplayName.of(itemSnapshotRepository.findById(event.snapshotId)?.name)))

    // 수신자별 라우팅 + 출처별 body 문구(#933). 위시 주인 → Wish(자기 wishId) + 위시 문구,
    // 토너먼트 등록자 → 자기 Tournament 좌표 + 토너먼트 문구. body 템플릿은 ${completionMessage} 한 변수라
    // 이 값이 통째로 채운다.
    override fun resolveRecipientContexts(
        event: ItemParsingCompleted,
        recipients: Set<UUID>,
    ): Map<UUID, RecipientContext> {
        val routings = recipientResolver.resolveRoutingsBySnapshot(event.snapshotId)
        return recipients.associateWith { userId ->
            // 방어: 수신자인데 좌표를 못 찾으면(등록 직후 경합 등) 위시 폴백 — wishId 없이도 알림은 나간다.
            val routing = routings[userId] ?: NotificationRouting.Wish(null)
            RecipientContext(
                routing = routing,
                variables = mapOf("completionMessage" to completionMessageOf(routing)),
            )
        }
    }

    private fun completionMessageOf(routing: NotificationRouting): String =
        when (routing.routingKind) {
            NotificationKind.TOURNAMENT -> TOURNAMENT_COMPLETION_MESSAGE
            else -> WISH_COMPLETION_MESSAGE
        }

    companion object {
        // body 템플릿(${completionMessage})에 채우는 출처별 완료 문구. 템플릿이 아니라 여기(코드)가 소유한다 —
        // 출처별로 갈라야 하는데 템플릿 테이블은 타입당 한 행(PK=type)이라 두 문구를 담을 자리가 없어서다.
        // 그래서 이 body 문구는 백오피스(#252) 편집 대상이 아니다(카탈로그 주석에도 남긴다). title 은 여전히 템플릿 소유.
        const val WISH_COMPLETION_MESSAGE = "위시 저장이 성공했어요"
        const val TOURNAMENT_COMPLETION_MESSAGE = "아이템이 등록됐어요"
    }
}
