package com.depromeet.piki.metrics.milestone

import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import com.depromeet.piki.common.config.AsyncConfig
import com.depromeet.piki.user.event.UserCreated
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

// 신규 가입마다 마일스톤 도달을 확인한다. 발행 트랜잭션 커밋 후(AFTER_COMMIT) 별도 스레드(@Async)에서 돈다 —
// 롤백 시 안 세고, 가입 응답을 Discord 호출로 막지 않는다.
// fallbackExecution=true: 게스트 생성(createGuest)은 트랜잭션 없이 즉시 커밋되는 경로라, 트랜잭션이 없을 때도
// 리스너가 돌아야 게스트 가입도 카운트에 반영된다.
// @ConditionalOnAdminEnabled: announcer 와 같은 조건(운영 백오피스/Discord 경계)에서만 뜬다. 꺼진 환경에선
// 이 리스너가 없어 UserCreated 이벤트는 구독자 없이 조용히 버려진다(무해).
@Component
@ConditionalOnAdminEnabled
class UserMilestoneListener(
    private val announcer: UserMilestoneAnnouncer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async(AsyncConfig.NOTIFICATION_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: UserCreated) {
        // 마일스톤 확인 실패가 가입 흐름·다른 부수효과에 절대 영향 주지 않게 삼킨다(부수 기능).
        runCatching { announcer.announce() }
            .onFailure { log.warn("user milestone 확인 실패 — userId={}", event.userId, it) }
    }
}
