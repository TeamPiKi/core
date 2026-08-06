package com.depromeet.piki.notification.controller.dto

import com.depromeet.piki.item.domain.ItemStatus

// SSE "silent-sync" 이벤트(event name = "silent-sync")의 data 로 직렬화되는 payload 봉투.
// silent-sync 는 "알림"(notification)이 아니라 조용한(silent) 화면 갱신 신호다 — 토스트·알림센터·FCM 표시 푸시를
// 거치지 않고 열린 SSE 연결로만 흐른다(NotificationSsePayload 와 별개 경로). 클라는 type 으로 갱신 사건을 분기한다.
//
// notification 봉투가 sealed + type 으로 여러 알림 셰입을 묶듯, silent-sync 도 sealed + type 으로 여러 갱신 사건을 묶는다.
// 첫 사건은 토너먼트 출전 아이템 파싱 완료/실패(TournamentItemParsed). 갱신 사건이 늘면 SilentSyncType + 구현체를 더한다.
sealed interface SilentSyncPayload {
    val type: SilentSyncType
}

// silent-sync 갱신 사건의 종류. 클라가 화면 갱신 동작을 분기하는 키.
enum class SilentSyncType {
    TOURNAMENT_ITEM_PARSED,
    UNREAD_COUNT_CHANGED,
}

// 토너먼트 출전 아이템의 파싱이 끝났음을 그 토너먼트 참여자 화면에 반영하는 갱신 신호.
// 클라는 (tournamentId, tournamentItemId)로 그 출전 카드를 찾아 status 로 갱신한다:
// PENDING/PROCESSING 로딩 → READY(상품 정보 표시) / FAILED(에러·재시도). status 는 항상 READY·FAILED 중 하나다.
data class TournamentItemParsed(
    val tournamentId: Long,
    val tournamentItemId: Long,
    val status: ItemStatus,
) : SilentSyncPayload {
    override val type: SilentSyncType = SilentSyncType.TOURNAMENT_ITEM_PARSED
}

// 읽음 처리 후 갱신된 안읽음 수를 그 유저의 온라인(SSE 연결) 기기 인앱 배지에 즉시 반영하는 갱신 신호(#487 의 SSE 보강).
// FCM silent push 가 오프라인 기기의 OS 아이콘 badge 를 맞추는 것과 짝 — 온라인 인앱 배지는 이 SSE 로 맞춘다(FCM-only 면
// 앱을 열어둔 기기의 인앱 숫자가 갱신되지 않는다). REST 읽음 응답(NotificationReadResponse)과 같은 셰입이라 클라가
// +1/-1 산수 없이 badge 를 그대로 미러링한다.
data class UnreadCountChanged(
    val unreadCount: Long,
) : SilentSyncPayload {
    override val type: SilentSyncType = SilentSyncType.UNREAD_COUNT_CHANGED

    companion object {
        fun of(unreadCount: Long): UnreadCountChanged = UnreadCountChanged(unreadCount = unreadCount)
    }
}
