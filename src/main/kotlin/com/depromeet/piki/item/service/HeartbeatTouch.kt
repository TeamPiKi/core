package com.depromeet.piki.item.service

import com.depromeet.piki.item.repository.ItemSnapshotRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

// 박동(heartbeat) fenced touch 의 짧은 트랜잭션 경계.
//
// ParsingHeartbeat 의 박동 루프·시작 가드가 이 빈을 호출한다. ParsingHeartbeat 는 @Scheduled(비-트랜잭션)라
// 자기 안에서 @Transactional 메서드를 직접 부르면 self-invocation 으로 프록시를 안 거쳐 트랜잭션이 무력화된다.
// 그래서 트랜잭션 경계를 이 별도 빈으로 떼어 프록시를 확실히 거치게 한다(CLAUDE.md '## 트랜잭션 경계' self-invocation).
//
// touch 는 항목별 단건 UPDATE 라 트랜잭션이 짧고, 동시 실행 상한이 워커 풀 크기(maxPool)라 부하는 무시 가능하다.
@Component
class HeartbeatTouch(
    private val itemSnapshotRepository: ItemSnapshotRepository,
) {
    // fenced touch — snapshotId 가 여전히 expectedAttempt 의 PROCESSING 이면 updated_at 을 현재시각으로 밀고 1을,
    // 재클레임됐거나 이미 전이해 소유권을 잃었으면 0을 반환한다. @Modifying 는 트랜잭션이 필요하므로 여기서 연다.
    @Transactional
    fun touch(
        snapshotId: Long,
        expectedAttempt: Int,
    ): Int = itemSnapshotRepository.touchHeartbeat(snapshotId, expectedAttempt, LocalDateTime.now())
}
