package com.depromeet.piki.notification.sse

import java.util.UUID

// SSE 로 내보내는 데이터 이벤트(notification·silent-sync)의 유저별 로그 — 재연결 replay 의 원천.
//
// 스트림을 "신뢰 가능"하게 만드는 축이다: 모든 데이터 이벤트가 emit 시점에 여기 적재되고 이벤트 id 를 부여받아,
// 클라이언트는 마지막으로 받은 id(Last-Event-ID) 하나로 끊김 동안의 이벤트를 종류 구분 없이 균일하게 복구한다.
// 로그는 연결 유무와 무관하게 적재된다 — 연결이 없던 동안의 이벤트도 다음 재연결에서 replay 대상이 된다.
interface SseEventLog {
    // 이벤트를 로그에 적재하고 부여된 이벤트 id 를 돌려준다. 적재 실패(로그 저장소 장애)는 null 로 degrade —
    // 호출자는 id 없이 live 전송만 한다. id 없는 이벤트는 SSE 프로토콜상 클라이언트의 lastEventId 를
    // 갱신하지 않으므로, 적재 안 된 이벤트가 복구 기준점을 오염시키지 않는다.
    fun append(
        userId: UUID,
        eventName: String,
        payloadJson: String,
    ): String?

    // lastEventId 초과분을 발생 순서(오래된 것부터)로 최대 limit 건. 조회 실패 시 빈 리스트(replay 생략 degrade).
    fun readAfter(
        userId: UUID,
        lastEventId: String,
        limit: Int,
    ): List<SseEventRecord>
}

// 로그에 적재된 이벤트 한 건. replay 는 이 원본(name·payload)을 그대로 다시 흘려보내 live 와 같은 와이어를 만든다.
data class SseEventRecord(
    val id: String,
    val eventName: String,
    val payloadJson: String,
)
