package com.depromeet.piki.notification.sse

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

// 재연결한 SSE 연결에 끊김 동안 쌓인 이벤트를 이벤트 로그에서 다시 흘려보낸다(Last-Event-ID 기반 catch-up).
// notification·silent-sync 구분 없이 로그에 적재된 모든 데이터 이벤트가 균일하게 복구된다 —
// 클라이언트는 "놓친 이벤트는 다시 온다, 이벤트 id 로 dedup" 규칙 하나만 가지면 된다.
@Component
class SseReconnectReplayer(
    private val sseEventLog: SseEventLog,
    private val localDelivery: LocalSseDelivery,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // lastEventId 초과분을 발생 순서대로 그 연결에만 replay 한다.
    //
    // 상한 초과(끊김이 아주 길었던 경우)면 replay 를 통째로 생략한다 — 일부만 보내면 replay 구간 뒤에
    // 조용한 구멍이 남아 "받은 만큼은 연속" 이라는 계약이 깨진다. 로그 trim 으로 lastEventId 이후가 잘린
    // 경우에도 잔존 건수가 반드시 MAX_LEN(> REPLAY_LIMIT)에 도달해 같은 생략 분기로 귀결되므로, 구멍 난
    // 구간을 연속인 척 replay 하는 일이 없다(RedisSseEventLog.MAX_LEN 주석 참조). 생략 시 복구는 기존
    // 계약(재연결 시 목록/배지 API 재조회)이 그대로 책임진다. 초과 감지를 위해 limit+1 건을 조회해 판정한다.
    fun replayMissed(
        userId: UUID,
        emitter: SseEmitter,
        lastEventId: String,
    ) {
        val missed = sseEventLog.readAfter(userId, lastEventId, REPLAY_LIMIT + 1)
        if (missed.isEmpty()) return
        if (missed.size > REPLAY_LIMIT) {
            // 유실 복구가 replay 대신 목록 재조회로 넘어가는 지점 — 빈도를 봐야 상한 적정성을 판단할 수 있어 info.
            log.info("SSE replay 생략(상한 {} 초과) userId={} lastEventId={}", REPLAY_LIMIT, userId, lastEventId)
            return
        }
        localDelivery.replayTo(userId, emitter, missed)
        log.info("SSE replay {}건 userId={} lastEventId={}", missed.size, userId, lastEventId)
    }

    companion object {
        // 한 번의 재연결에 replay 하는 최대 이벤트 수. RedisSseEventLog.MAX_LEN 보다 반드시 작아야 한다(위 주석).
        // 이 상한을 넘는 공백은 "짧은 끊김" 이 아니라 장기 미접속이라 목록 재조회가 맞는 복구 경로다.
        const val REPLAY_LIMIT = 100
    }
}
