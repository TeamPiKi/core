package com.depromeet.piki.item.service

import com.depromeet.piki.item.repository.ItemSnapshotRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

// 파싱 단건의 **소유권** 을 다루는 짧은 트랜잭션 경계 — 획득(acquire)과 갱신(renew) 두 가지다.
//
// 소유권 토큰은 attemptCount 이고, 두 연산 모두 조건부 UPDATE 의 영향 행 수로 판정한다(원자적 test-and-set):
//   - acquire: 워커가 실행에 진입하는 순간 attempt 를 +1 하며 이 시도를 자기 것으로 만든다. **시도 소모는 여기서만** 일어나므로,
//     집혔지만 제출이 거부돼 실행이 0회인 행은 예산을 잃지 않는다.
//   - renew  : 실행 중 살아있음을 새긴다(박동). attempt 는 건드리지 않아 소유권이 옮겨가지 않는다.
// 소유권 반납은 별도 연산이 없다 — 전이(READY/FAILED)가 status 를 터미널로 바꿔 두 조건을 동시에 깨뜨린다.
//
// ParsingHeartbeat 는 @Scheduled(비-트랜잭션)라 자기 안에서 @Transactional 메서드를 직접 부르면 self-invocation 으로
// 프록시를 안 거쳐 트랜잭션이 무력화된다. 그래서 경계를 이 별도 빈으로 떼어 프록시를 확실히 거치게 한다
// (CLAUDE.md '## 트랜잭션 경계' self-invocation).
@Component
class ParsingOwnership(
    private val itemSnapshotRepository: ItemSnapshotRepository,
) {
    // 소유권 획득. 성공하면 이 시도의 토큰(직전 attempt + 1)을, 이미 남이 가져갔거나 종결됐으면 null 을 돌려준다.
    @Transactional
    fun acquire(
        snapshotId: Long,
        expectedAttempt: Int,
    ): Int? {
        val acquired = itemSnapshotRepository.acquireOwnership(snapshotId, expectedAttempt, LocalDateTime.now())
        if (acquired == 0) return null
        return expectedAttempt + 1
    }

    // 소유권 갱신(박동). 1 = 유지, 0 = 상실(재획득됐거나 이미 전이).
    @Transactional
    fun renew(
        snapshotId: Long,
        attempt: Int,
    ): Int = itemSnapshotRepository.renewOwnership(snapshotId, attempt, LocalDateTime.now())
}
