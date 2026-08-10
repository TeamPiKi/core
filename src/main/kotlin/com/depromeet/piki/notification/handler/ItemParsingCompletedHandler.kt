package com.depromeet.piki.notification.handler

import com.depromeet.piki.item.event.ItemParsingCompleted
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.notification.domain.NotificationRouting
import com.depromeet.piki.notification.domain.NotificationType
import org.springframework.stereotype.Component
import java.text.BreakIterator
import java.util.UUID

// 아이템 파싱 완료 알림. 위시·토너먼트 어느 쪽으로 올린 아이템이든 동일하게 "{이름} 파싱이 완료되었어요" 를 알린다 —
// 수신자는 snapshotId(버전)를 역조회해 위시 주인 ∪ 토너먼트 참가자로 모은다(ItemParsingRecipientResolver, 실패 알림과 공유).
// 문구에 어떤 아이템인지 담으려고(#895) 그 버전(snapshot)의 name 을 조회해 itemName 변수로 채운다 — 긴 이름은 displayName 이 자른다.
@Component
class ItemParsingCompletedHandler(
    private val recipientResolver: ItemParsingRecipientResolver,
    private val itemSnapshotRepository: ItemSnapshotRepository,
) : NotificationEventHandler<ItemParsingCompleted>(NotificationType.ITEM_PARSING_COMPLETED) {
    override fun resolveRefId(event: ItemParsingCompleted): Long = event.itemId

    override fun resolveRecipients(event: ItemParsingCompleted): Set<UUID> = recipientResolver.resolve(event.snapshotId)

    override fun resolveRouting(event: ItemParsingCompleted): NotificationRouting = recipientResolver.resolveRouting(event.snapshotId)

    // 문구 변수 itemName — 그 버전(snapshot)의 name 을 조회해 표시용으로 자른다.
    // best-effort: 버전이 없거나 이름이 비어도 이름 하나 때문에 알림 전체를 떨구지 않고 기본값을 쓴다
    // (토너먼트 알림의 tournamentName fallback 과 같은 결).
    override fun resolveActorContext(event: ItemParsingCompleted): ActorContext =
        ActorContext(variables = mapOf("itemName" to displayName(itemSnapshotRepository.findById(event.snapshotId)?.name)))

    companion object {
        const val MAX_NAME_LENGTH = 10

        // grapheme 절단 뒤 한 번 더 거는 UTF-16 char 상한. 클러스터 1개가 조합 부호로 수십~수백 char 일 수 있어
        // "10글자" 가 곧 짧은 문자열을 뜻하지 않는다. 그대로 두면 Notification 생성자의 require(title.length <= 255)에
        // 걸리고, dispatcher 의 수신자별 runCatching 이 그 예외를 삼켜 완료 알림이 전 수신자에게 조용히 누락된다.
        // 문구 나머지("… 파싱이 완료되었어요")를 감안해 넉넉히 남긴다.
        private const val MAX_NAME_CHARS = 100
        private const val ELLIPSIS = "…"
        private const val FALLBACK_NAME = "상품"

        // 알림·푸시 한 줄을 유지하도록 10자 초과면 앞 10자 + … 로 자른다. 이름이 없거나 공백뿐이면 기본값.
        // 절단 단위는 grapheme cluster(BreakIterator) 다 — String.length·take 는 UTF-16 코드 단위라, 이모지(surrogate
        // pair)·조합문자가 10번째 경계에 걸치면 반쪽만 남아 깨진 문자로 노출된다(#896 CodeRabbit). 사용자가 보는 "글자"
        // 경계로 잘라 깨짐을 막는다.
        //
        // 공백은 먼저 한 칸으로 접는다 — 추출 이름은 상류(ProductSnapshot)에서 trim 되지 않아 앞뒤 공백·개행이 그대로
        // 온다. 앞 공백이 10글자 예산을 먹어 이름이 일찍 잘리고, 개행이 섞이면 "한 줄 유지" 목표 자체가 깨진다.
        fun displayName(rawName: String?): String {
            val name = rawName?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return FALLBACK_NAME
            val boundary = BreakIterator.getCharacterInstance().apply { setText(name) }
            var end = boundary.first()
            repeat(MAX_NAME_LENGTH) {
                val next = boundary.next()
                if (next == BreakIterator.DONE) return name.capChars() // 표시 글자 수가 한도 이하 — 자를 필요 없음
                end = next
            }
            // 한도째 경계까지 왔다. 그 뒤에 글자가 더 있으면(다음 경계가 DONE 이 아니면) 잘라서 말줄임표를 붙인다.
            return if (boundary.next() == BreakIterator.DONE) name.capChars() else name.substring(0, end).capChars() + ELLIPSIS
        }

        // 마지막 안전망 — grapheme 경계와 무관하게 char 길이를 자른다. 여기 걸리는 입력은 정상 상품명이 아니라
        // 조합 부호를 쌓아 만든 비정상 값이라, 글자 깨짐보다 알림 누락을 막는 쪽을 택한다.
        private fun String.capChars(): String = if (length <= MAX_NAME_CHARS) this else take(MAX_NAME_CHARS) + ELLIPSIS

        private val WHITESPACE = Regex("\\s+")
    }
}
