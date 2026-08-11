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
// body 문구는 등록 출처에 따라 갈린다 — 위시에 직접 담은 것과 토너먼트에 직접 올린 것은 사용자에게 다른 사건이다
// (토너먼트에 올려도 위시리스트에는 안 들어간다). 출처 판정은 라우팅이 이미 하고 있어 그대로 재사용한다.
@Component
class ItemParsingCompletedHandler(
    private val recipientResolver: ItemParsingRecipientResolver,
    private val itemSnapshotRepository: ItemSnapshotRepository,
) : NotificationEventHandler<ItemParsingCompleted>(NotificationType.ITEM_PARSING_COMPLETED) {
    override fun resolveRefId(event: ItemParsingCompleted): Long = event.itemId

    override fun resolveRecipients(event: ItemParsingCompleted): Set<UUID> = recipientResolver.resolve(event.snapshotId)

    override fun resolveRouting(event: ItemParsingCompleted): NotificationRouting = recipientResolver.resolveRouting(event.snapshotId)

    // 문구 변수 둘 — itemName(제목)·completionMessage(본문).
    // best-effort: 버전이 없거나 이름이 비어도 이름 하나 때문에 알림 전체를 떨구지 않고 기본값을 쓴다
    // (토너먼트 알림의 tournamentName fallback 과 같은 결).
    //
    // 이벤트당 한 번만 호출된다(dispatcher 가 수신자 루프 밖에서 부른다) — 라우팅 조회가 한 번 더 들어가도 수신자
    // 수와 무관하다.
    override fun resolveActorContext(event: ItemParsingCompleted): ActorContext =
        ActorContext(
            variables =
                mapOf(
                    "itemName" to ItemDisplayName.of(itemSnapshotRepository.findById(event.snapshotId)?.name),
                    "completionMessage" to completionMessageOf(resolveRouting(event)),
                ),
        )

    // 문장을 통째로 변수로 채운다. notification_templates 는 타입당 한 행(PK=type)이라 위시용·토너먼트용 body 를
    // 따로 둘 자리가 없고, 두 문장이 구조도 달라("위시 저장이 성공" vs "아이템이 등록") 공통 뼈대 + 변수로도 안 쪼개진다.
    // 대가로 이 body 문구는 백오피스(#252)에서 편집할 수 없다 — title 은 여전히 템플릿이 소유한다.
    private fun completionMessageOf(routing: NotificationRouting): String =
        when (routing) {
            NotificationRouting.Wish -> WISH_MESSAGE
            is NotificationRouting.Tournament -> TOURNAMENT_MESSAGE
        }

    companion object {
        const val WISH_MESSAGE = "위시 저장이 성공했어요"
        const val TOURNAMENT_MESSAGE = "아이템이 등록됐어요"
    }
}
