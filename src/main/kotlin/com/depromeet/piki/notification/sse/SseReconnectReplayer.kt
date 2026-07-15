package com.depromeet.piki.notification.sse

import com.depromeet.piki.notification.repository.NotificationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

// 재연결한 SSE 연결에 끊김 동안 쌓인 notification 을 다시 흘려보낸다(Last-Event-ID 기반 catch-up).
// notification 은 DB 영속이라 어느 인스턴스가 재연결을 받아도 같은 결과를 replay 할 수 있다 —
// 인메모리 버퍼가 아니라 DB 를 원천으로 삼아 재시작·blue-green·스케일아웃과 무관하다.
//
// 조회는 repository 안의 짧은 트랜잭션으로 끝나고 emitter write(외부 I/O)는 트랜잭션 밖이다
// (CLAUDE.md "외부 호출은 트랜잭션 밖에서" 와 같은 결 — 느린 클라이언트 write 가 DB 커넥션을 잡지 않는다).
@Component
class SseReconnectReplayer(
    private val notificationRepository: NotificationRepository,
    private val localDelivery: LocalSseDelivery,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // lastEventId(클라이언트가 마지막으로 받은 알림 id) 초과분을 발생 순서대로 그 연결에만 replay 한다.
    //
    // 상한 초과(끊김이 아주 길었던 경우)면 replay 를 통째로 생략한다 — 일부만 보내면 replay 구간 뒤에
    // 조용한 구멍이 남아 "받은 만큼은 연속" 이라는 계약이 깨진다. 생략 시 복구는 기존 계약(재연결 시
    // 목록/배지 API 재조회)이 그대로 책임진다. 상한은 초과 감지를 위해 limit+1 건을 조회해 판정한다.
    fun replayMissed(
        userId: UUID,
        emitter: SseEmitter,
        lastEventId: Long,
    ) {
        val missed = notificationRepository.findAfterId(userId, lastEventId, REPLAY_LIMIT + 1)
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
        // 한 번의 재연결에 replay 하는 최대 알림 수. 유저당 알림 발생량(토너먼트·파싱 사건)에 비해 넉넉한 값으로,
        // 이 상한을 넘는 공백은 "짧은 끊김" 이 아니라 장기 미접속이라 목록 재조회가 맞는 복구 경로다.
        const val REPLAY_LIMIT = 100
    }
}
